package qupath.ext.biop.servers.omero.raw;

/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */


import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import omero.gateway.model.ShapeData;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;

import omero.gateway.model.*;

class OmeroRawShapes {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawShapes.class);


    /**
     * Convert PathObjects into OMERO-readable objects. In case the PathObject contains holes, it is split
     * into individual shapes and each shape will be part of the same OMERO ROI (nested hierarchy in OMERO.web).
     * @param src : pathObject
     * @return
     */
    public static List<ShapeData> convertQuPathRoiToOmeroRoi(PathObject src, String objectID, String parentID) {
        ROI roi = src.getROI();

        List<ShapeData> shapes = new ArrayList<>();
        if (roi instanceof RectangleROI) {
            // Build the OMERO object
            RectangleData rectangle = new RectangleData(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight());
            // Write in comments the type of PathObject as well as the assigned class if there is one
            rectangle.setText(setRoiComment(src, objectID, parentID));

            // set the ROI position in the image
            rectangle.setC(roi.getC());
            rectangle.setT(roi.getT());
            rectangle.setZ(roi.getZ());
            shapes.add(rectangle);

        } else if (roi instanceof EllipseROI) {
            EllipseData ellipse = new EllipseData(roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth()/2, roi.getBoundsHeight()/2);
            ellipse.setText(setRoiComment(src, objectID, parentID));
            ellipse.setC(roi.getC());
            ellipse.setT(roi.getT());
            ellipse.setZ(roi.getZ());
            shapes.add(ellipse);

        } else if (roi instanceof LineROI) {
            LineROI lineRoi = (LineROI)roi;
            LineData line = new LineData(lineRoi.getX1(), lineRoi.getY1(), lineRoi.getX2(), lineRoi.getY2());
            line.setText(setRoiComment(src, objectID, parentID));
            line.setC(roi.getC());
            line.setT(roi.getT());
            line.setZ(roi.getZ());
            shapes.add(line);

        } else if (roi instanceof PolylineROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolylineData polyline = new PolylineData(points);
            polyline.setText(setRoiComment(src, objectID, parentID));
            polyline.setC(roi.getC());
            polyline.setT(roi.getT());
            polyline.setZ(roi.getZ());
            shapes.add(polyline);

        } else if (roi instanceof PolygonROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolygonData polygon = new PolygonData(points);
            polygon.setText(setRoiComment(src, objectID, parentID));
            polygon.setC(roi.getC());
            polygon.setT(roi.getT());
            polygon.setZ(roi.getZ());
            shapes.add(polygon);

        } else if (roi instanceof PointsROI) {
            List<Point2> roiPoints = roi.getAllPoints();

            for (Point2 roiPoint : roiPoints) {
                PointData point = new PointData(roiPoint.getX(), roiPoint.getY());
                point.setText(setRoiComment(src, objectID, parentID));
                point.setC(roi.getC());
                point.setT(roi.getT());
                point.setZ(roi.getZ());
                shapes.add(point);
            }

        } else if (roi instanceof GeometryROI) {
            // MultiPolygon
            logger.info("MultiPolygon will be split for OMERO compatibility.");

            // split the ROI into each individual shape
            List<ROI> rois = RoiTools.splitROI(roi);
            rois = splitHolesAndShape(rois);

            // process each individual shape
            for (ROI value : rois) {
                if(!(value ==null))
                    shapes.addAll(convertQuPathRoiToOmeroRoi(PathObjects.createAnnotationObject(value),objectID,parentID));
            }

        } else {
            logger.warn("Unsupported type {}", roi.getRoiName());
            return null;
        }

        return shapes;
    }


    private static String setRoiComment(PathObject src, String objectID, String parentID){
        if (src.isDetection()) {
             return src.getPathClass() != null ? "Detection:"+src.getPathClass().getName()+":"+objectID+":"+parentID : "Detection:NoClass:"+objectID+":"+parentID;
        } else {
            return src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName()+":"+objectID+":"+parentID : "Annotation:NoClass:"+objectID+":"+parentID;
        }
    }


    /**
     * Split holes and envelop of the same polygon into independent ROIs.
     *
     * @param rois List of split ROIs
     * @return list of ROIs of decoupling external and internal rings
     */
    private static List<ROI> splitHolesAndShape(List<ROI> rois) {
        List<ROI> polygonROIs = new ArrayList<>();
        for (ROI roi : rois) {
            // Only process shapes that are polygons
            if (roi.getGeometry() instanceof org.locationtech.jts.geom.Polygon) {
                // get the external shape of the polygon
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) roi.getGeometry();

                Coordinate[] polygonCoordinates = polygon.getExteriorRing().getCoordinates();
                List<Point2> polygonROICoordinates = new ArrayList<>();

                for (Coordinate polygonCoordinate : polygonCoordinates) {
                    polygonROICoordinates.add(new Point2(polygonCoordinate.x, polygonCoordinate.y));
                }

                polygonROIs.add(createROI(polygonROICoordinates, roi));

                // get the internal shapes of the polygon (i.e. holes)
                for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
                    polygonCoordinates = polygon.getInteriorRingN(j).getCoordinates();
                    polygonROICoordinates = new ArrayList<>();

                    for (Coordinate polygonCoordinate : polygonCoordinates) {
                        polygonROICoordinates.add(new Point2(polygonCoordinate.x, polygonCoordinate.y));
                    }

                    polygonROIs.add(createROI(polygonROICoordinates, roi));
                }
            } else {
                polygonROIs.add(roi);
            }
        }
        return polygonROIs;
    }

    /**
     * According to specificities of the shape coordinates, rectangle or polygon ROI is created
     * @param polygonROICoordinates
     * @param roi
     * @return
     */
    private static ROI createROI(List<Point2> polygonROICoordinates, ROI roi){
        if (polygonROICoordinates.size() == 5) {
            if(polygonROICoordinates.get(0).getX()==polygonROICoordinates.get(1).getX() &&
               polygonROICoordinates.get(1).getY()==polygonROICoordinates.get(2).getY() &&
               polygonROICoordinates.get(2).getX()==polygonROICoordinates.get(3).getX() &&
               polygonROICoordinates.get(3).getY()==polygonROICoordinates.get(4).getY()){
                double maxX = Collections.max(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double minX = Collections.min(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double maxY = Collections.max(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));
                double minY = Collections.min(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));

                return ROIs.createRectangleROI(minX, minY, maxX-minX, maxY-minY,
                        roi.getImagePlane());
            }
            else
                return ROIs.createPolygonROI(polygonROICoordinates, roi.getImagePlane());
        }
        else{
            //TODO find a way to capture precisely if the polygon is an ellipse or not
            /*List<Double> distance = new ArrayList<>();
            double dist = 0;
            for(int i = 0; i < polygonROICoordinates.size()-1; i++){
                Point2 p1 = polygonROICoordinates.get(i);
                Point2 p2 = polygonROICoordinates.get(i+1);
                dist += p1.distance(p2);
                distance.add(p1.distance(p2));
            }
            double distMean = 0;
            AtomicReference<Double> distStd = new AtomicReference<>((double) 0);
            if (polygonROICoordinates.size() != 0) {
                distMean = dist / polygonROICoordinates.size();
                double finalDist = distMean;
                distance.forEach(c-> distStd.updateAndGet(v -> v + Math.pow(c - finalDist, 2)));
                distStd.set(Math.sqrt(distStd.get() / polygonROICoordinates.size()));
            }
            System.out.println("Std des distances   "+distStd.get());
           System.out.println("Mean des distances   "+distMean);
            if(distStd.get() < 10){
                double maxX = Collections.max(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double minX = Collections.min(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double maxY = Collections.max(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));
                double minY = Collections.min(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));

                return ROIs.createEllipseROI(minX, minY, (maxX-minX), (maxY-minY), roi.getImagePlane());
            }
            else*/
                return ROIs.createPolygonROI(polygonROICoordinates, roi.getImagePlane());

        }
    }
}

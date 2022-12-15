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


import java.awt.Color;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import omero.gateway.model.ShapeData;
import omero.model.*;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;

import omero.gateway.model.*;


class OmeroRawShapes {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawShapes.class);


    /**
     * Create a PathObject with of certain type (annotation, detection,...)
     * with no class and the default red color
     *
     * @param roi
     * @param roiType
     * @return
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType){
        return createPathObjectFromQuPathRoi(roi, roiType,"");
    }

    /**
     * Create a PathObject with of certain type (annotation, detection,...)
     * with a certain color and without any class
     *
     * @param roi
     * @param roiType
     * @return
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType, Color color){
        PathObject pathObject = createPathObjectFromQuPathRoi(roi, roiType, "");

        // check if the color is not the default color (yellow) for selected objects
        if(color == null || color.equals(Color.YELLOW))
            pathObject.setColor(Color.RED.getRGB());
        else pathObject.setColor(color.getRGB());

        return pathObject;
    }


    /**
     * Create a PathObject with of type annotation or detection
     * with the specified class and the color associated to the class.
     *
     * Currently, pathObjects of "cell" type are not supported and are considered as detections.
     * All pathObjects of other type are automatically assigned to annotation type.
     *
     * @param roi
     * @param roiType
     * @param roiClass
     * @return
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType, String roiClass){
        PathObject pathObject;
        boolean isValidClass = !(roiClass == null || roiClass.isEmpty() || roiClass.equalsIgnoreCase("noclass"));

        // get all potential classes of a ROI
        List<String> classes = new ArrayList<>();
        if(isValidClass)
            classes.addAll(Arrays.stream(roiClass.split("&")).collect(Collectors.toList()));

        // code breaks all the current project
        // do not use
        // and wait for the version TODO

       /* // create new PathClasses if they are not already created
        List<PathClass> availablePathClasses = QPEx.getQuPath().getProject().getPathClasses();
        List<PathClass> newPathClasses = new ArrayList<>();

        for (PathClass pathClass : availablePathClasses) {
            newPathClasses.add(pathClass);
            for (String pathClassName : classes)
                if (pathClass.getName() != null && !pathClass.getName().equals(pathClassName))
                    newPathClasses.add(PathClassFactory.getPathClass(pathClassName));
        }
        QPEx.getQuPath().getProject().setPathClasses(newPathClasses);*/


        switch(roiType.toLowerCase()){
            case "cell": roiType = "detection";
            case "detection":
                if (!isValidClass)
                    pathObject = PathObjects.createDetectionObject(roi);
                else
                    pathObject = PathObjects.createDetectionObject(roi, PathClass.fromCollection(classes));
                break;
            case "annotation":
            default:
                if (!isValidClass)
                    pathObject = PathObjects.createAnnotationObject(roi);
                else
                    pathObject = PathObjects.createAnnotationObject(roi, PathClass.fromCollection(classes));
                break;
        }

        if(!isValidClass)
            pathObject.setColor(Color.RED.getRGB());

        return pathObject;
    }


    /**
     * convert Omero ROIs To QuPath ROIs.
     * For annotations, takes into account complex ROIs (with multiple shapes) by applying a XOR operation to reduce the dimensionality.
     * For detections, no complex ROIs are possible. So, each shape = one ROI
     *
     * @param roiDatum
     * @return
     */
    public static ROI convertOmeroROIsToQuPathROIs(ROIData roiDatum) {
        // Convert OMERO ROIs into QuPath ROIs
        List<ROI> roi = convertOmeroShapesToQuPathROIs(roiDatum);

        if (!roi.isEmpty()) {
            // get the number of ROI "Point" in all shapes attached the current ROI
            // Points are not supported during the XOR operation ; they are processed differently.
            long nbPoints = roi.stream().filter(e -> e.getRoiName().equals("Points")).count();
            ROI finalROI = roi.get(0);

            // process ROIs with multiple points only
            if (nbPoints == roi.size() && roi.size() > 1) {
                // create a pointsROI instance with multiple points
                finalROI = ROIs.createPointsROI(roi.stream().mapToDouble(ROI::getCentroidX).toArray(),
                        roi.stream().mapToDouble(ROI::getCentroidY).toArray(),
                        ImagePlane.getPlaneWithChannel(roi.get(0).getC(), Math.max(roi.get(0).getZ(), 0), Math.max(roi.get(0).getT(), 0)));
            }

            // Process ROIs with multiple shapes, containing one or more points
            else if (nbPoints > 0 && roi.size() > 1) {
                List<ROI> pointsList = roi.stream().filter(e -> e.getRoiName().equals("Points")).collect(Collectors.toList());
                List<ROI> notPointsList = roi.stream().filter(e -> !e.getRoiName().equals("Points")).collect(Collectors.toList());

                // create a pointsROI instance with multiple points
                ROI pointsROI = ROIs.createPointsROI(pointsList.stream().mapToDouble(ROI::getCentroidX).toArray(),
                        pointsList.stream().mapToDouble(ROI::getCentroidY).toArray(),
                        ImagePlane.getPlaneWithChannel(pointsList.get(0).getC(), Math.max(pointsList.get(0).getZ(), 0), Math.max(pointsList.get(0).getT(), 0)));

                // make a complex roi by applying XOR operation between shapes
                finalROI = notPointsList.get(0);
                for (int k = 1; k < notPointsList.size(); k++) {
                    finalROI = linkShapes(finalROI, notPointsList.get(k));
                }

                // make the union between points and complex ROI
                finalROI = RoiTools.combineROIs(finalROI, pointsROI, RoiTools.CombineOp.ADD);
            }

            // Process ROIs with single shape AND ROIs with multiple shapes that do not contain points
            else {
                for (int k = 1; k < roi.size(); k++) {
                    // make a complex roi by applying XOR operation between shapes
                    finalROI = linkShapes(finalROI, roi.get(k));
                }
            }
            return finalROI;
        }
        return null;
    }

    /**
     * convert Omero ROIs To QuPath ROIs.
     *
     * *********************** BE CAREFUL *****************************
     * For the z and t in the ImagePlane, if z < 0 and t < 0 (meaning that roi should be present on all the slices/frames),
     * only the first slice/frame is taken into account (meaning that roi are only visible on the first slice/frame)
     * ****************************************************************
     *
     * @param roiData
     * @return list of QuPath ROIs
     */
    private static List<ROI> convertOmeroShapesToQuPathROIs(ROIData roiData){
        // get the ROI
        Roi omeROI = (Roi) roiData.asIObject();

        // get the shapes contained in the ROI (i.e. holes or something else)
        List<Shape> shapes = omeROI.copyShapes();
        List<ROI> list = new ArrayList<>();

        // Iterate on shapes, select the correct instance and create the corresponding QuPath ROI
        for (Shape shape:shapes) {

            if(shape instanceof Rectangle){
                RectangleData s = new RectangleData(shape);
                list.add(ROIs.createRectangleROI(s.getX(),s.getY(),s.getWidth(),s.getHeight(),ImagePlane.getPlaneWithChannel(s.getC(), Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Ellipse){
                EllipseData s = new EllipseData(shape);
                list.add(ROIs.createEllipseROI(s.getX()-s.getRadiusX(),s.getY()-s.getRadiusY(),s.getRadiusX()*2, s.getRadiusY()*2,ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof omero.model.Point){
                PointData s = new PointData(shape);
                list.add(ROIs.createPointsROI(s.getX(),s.getY(),ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Polyline){
                PolylineData s = new PolylineData(shape);
                list.add(ROIs.createPolylineROI(s.getPoints().stream().mapToDouble(Point2D.Double::getX).toArray(),
                        s.getPoints().stream().mapToDouble(Point2D.Double::getY).toArray(),
                        ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof omero.model.Polygon){
                PolygonData s = new PolygonData(shape);
                list.add(ROIs.createPolygonROI(s.getPoints().stream().mapToDouble(Point2D.Double::getX).toArray(),
                        s.getPoints().stream().mapToDouble(Point2D.Double::getY).toArray(),
                        ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Label){
                logger.warn("No ROIs created (requested label shape is unsupported)");
                //s=new TextData(shape);

            }else if(shape instanceof Line){
                LineData s = new LineData(shape);
                list.add(ROIs.createLineROI(s.getX1(),s.getY1(),s.getX2(),s.getY2(),ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Mask){
                logger.warn("No ROIs created (requested Mask shape is not supported yet)");
                //s=new MaskData(shape);
            }else{
                logger.warn("Unsupported shape ");
            }
        }

        return list;
    }


    /**
     * Output the ROI result of the XOR operation between the 2 input ROIs
     *
     * 	 * *********************** BE CAREFUL *****************************
     * 	 * For the c, z and t in the ImagePlane, if the rois contains in the general ROI are not contained in the same plane,
     * 	 * the new composite ROI are set on the lowest c/z/t plane
     * 	 * ****************************************************************
     *
     * @param roi1
     * @param roi2
     * @return ROI resulting of the XOR operation
     */
    private static ROI linkShapes(ROI roi1, ROI roi2){
        // get the area of the first roi
        Area a1 = new Area(roi1.getShape());

        // get the area of the second roi
        Area a2 = new Area(roi2.getShape());

        // Apply a xor operation on both area to combine them (ex. make a hole)
        a1.exclusiveOr(a2);

        // get the area of the newly created area
        java.awt.Rectangle r = a1.getBounds();

        // Assign the new ROI to the lowest valid plan of the stack
        return ROIs.createAreaROI(a1, ImagePlane.getPlaneWithChannel(Math.min(roi1.getC(), roi2.getC()),
                Math.min(roi1.getZ(), roi2.getZ()),
                Math.min(roi1.getT(), roi2.getT())));
    }




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
                    shapes.addAll(convertQuPathRoiToOmeroRoi(PathObjects.createAnnotationObject(value, src.getPathClass()),objectID,parentID));
            }

        } else {
            logger.warn("Unsupported type {}", roi.getRoiName());
            return null;
        }
        return shapes;
    }


    /**
     * Write a string to populate ROI comment and have access to the type,class and parent of each object.
     *
     * @param src
     * @param objectID
     * @param parentID
     * @return
     */
    private static String setRoiComment(PathObject src, String objectID, String parentID){

        // format classes to OMERO-compatible string
        String pathClass = src.getPathClass() == null ? "NoClass" : src.getPathClass().toString().replaceAll(" ","").replaceAll(":","&");

        if (src.isDetection()) {
             return "Detection:"+pathClass+":"+objectID+":"+parentID;
        } else {
            return "Annotation:"+pathClass+":"+objectID+":"+parentID;
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
     *
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
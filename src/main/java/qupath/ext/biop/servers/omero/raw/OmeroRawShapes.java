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

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import omero.gateway.model.ShapeData;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.ROI;

import omero.gateway.model.*;

class OmeroRawShapes {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawShapes.class);


    public static List<ShapeData> convertQuPathRoiToOmeroRoi(PathObject src) {
        ROI roi = src.getROI();

        List<ShapeData> shapes = new ArrayList<>();
        if (roi instanceof RectangleROI) {
            RectangleData rectangle = new RectangleData(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight());
            if (src.isDetection()) {
                rectangle.setText(src.getPathClass()!= null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
            } else {
                rectangle.setText(src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
            }

            rectangle.setC(roi.getC());
            rectangle.setT(roi.getT());
            rectangle.setZ(roi.getZ());
            shapes.add(rectangle);

        } else if (roi instanceof EllipseROI) {
            EllipseData ellipse = new EllipseData(roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth()/2, roi.getBoundsHeight()/2);
            if (src.isDetection()) {
                ellipse.setText(src.getPathClass() != null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
            } else {
                ellipse.setText(src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
            }
            ellipse.setC(roi.getC());
            ellipse.setT(roi.getT());
            ellipse.setZ(roi.getZ());
            shapes.add(ellipse);

        } else if (roi instanceof LineROI) {
            LineROI lineRoi = (LineROI)roi;
            LineData line = new LineData(lineRoi.getX1(), lineRoi.getY1(), lineRoi.getX2(), lineRoi.getY2());
            if (src.isDetection()) {
                line.setText(src.getPathClass() != null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
            } else {
                line.setText(src.getPathClass()!= null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
            }
            line.setC(roi.getC());
            line.setT(roi.getT());
            line.setZ(roi.getZ());
            shapes.add(line);

        } else if (roi instanceof PolylineROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolylineData polyline = new PolylineData(points);
            if (src.isDetection()) {
                polyline.setText(src.getPathClass() != null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
            } else {
                polyline.setText(src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
            }
            polyline.setC(roi.getC());
            polyline.setT(roi.getT());
            polyline.setZ(roi.getZ());
            shapes.add(polyline);

        } else if (roi instanceof PolygonROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolygonData polygon = new PolygonData(points);
            if (src.isDetection()) {
                polygon.setText(src.getPathClass() != null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
            } else {
                polygon.setText(src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
            }
            polygon.setC(roi.getC());
            polygon.setT(roi.getT());
            polygon.setZ(roi.getZ());
            shapes.add(polygon);

        } else if (roi instanceof PointsROI) {
            List<Point2> roiPoints = roi.getAllPoints();

            for (Point2 roiPoint : roiPoints) {
                PointData point = new PointData(roiPoint.getX(), roiPoint.getY());
                if (src.isDetection()) {
                    point.setText(src.getPathClass() != null ? "Detection:"+src.getPathClass().getName() : "Detection:NoClass");
                } else {
                    point.setText(src.getPathClass() != null ? "Annotation:"+src.getPathClass().getName() : "Annotation:NoClass");
                }
                point.setC(roi.getC());
                point.setT(roi.getT());
                point.setZ(roi.getZ());
                shapes.add(point);
            }

        } else if (roi instanceof GeometryROI) {
            // MultiPolygon
            logger.info("MultiPolygon will be split for OMERO compatibility.");

            List<ROI> rois = RoiTools.splitROI(roi);

            //rois.forEach(e->System.out.println("ROIname : "+e.getRoiName()));

           /* for (ROI value : rois) {
                System.out.println("roi instanceof PointsROI : " + (value instanceof PointsROI));
                System.out.println("roi instanceof LineROI : " + (value instanceof LineROI));
                System.out.println("roi instanceof RectangleROI : " + (value instanceof RectangleROI));
                System.out.println("roi instanceof EllipseROI : " + (value instanceof EllipseROI));
                System.out.println("roi instanceof PolygonROI : " + (value instanceof PolygonROI));
                System.out.println("roi instanceof PolylineROI : " + (value instanceof PolylineROI));
                System.out.println("value.getGeometry().getNumGeometries() : "+value.getGeometry().getNumGeometries());
                System.out.println("value.getGeometry().getenveloppe.getNumGeometries() : "+value.getGeometry().getEnvelope().getNumGeometries());
                System.out.println("roi :"+value);
                System.out.println("roi.getgeometry :"+value.getGeometry());
                System.out.println("roi.getgeometry.getenveloppe :"+value.getGeometry());
                System.out.println("roi.getgeometry.getenveloppeinternal :"+value.getGeometry().getEnvelopeInternal());

            }*/

            rois = splitHolesAndShape(rois);

            for (ROI value : rois) {

               /* List<Point2D.Double> points = new ArrayList<>();
                value.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
                PolygonData polygon = new PolygonData(points);
                polygon.setText(src.getName() != null ? src.getName() : "");*/
                if(!(value ==null))
                    shapes.addAll(convertQuPathRoiToOmeroRoi(PathObjects.createAnnotationObject(value)));
               // shape.setText(src.getName() != null ? src.getName() : "");
               // shape.setFillColor(pathClass != null ? ARGBToRGBA(pathClass.getColor()) : -256);
                // polygons[i] = context.serialize(shape, Polygon.class);
            }

           // return null;//context.serialize(polygons);

        } else {
            logger.warn("Unsupported type {}", roi.getRoiName());
            return null;
        }

        // Set the appropriate colors
        /*if (src.getPathClass() != null) {
            int classColor = ARGBToRGBA(src.getPathClass().getColor());
            shape.setFillColor(classColor);
            shape.setStrokeColor(classColor);
        } else {
            shape.setFillColor(-256);	// Transparent
            shape.setStrokeColor(ARGBToRGBA(PathPrefs.colorDefaultObjectsProperty().get())); // Default Qupath object color
        }

        shape.setText(src.getName() != null ? src.getName() : "");*/
        return shapes;//context.serialize(shape, type);
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
                /*System.out.println("Roi.polygon.getBoundary() : "+polygon.getBoundary());
                System.out.println("polygon geometry type : "+polygon.getExteriorRing().getGeometryType());
                System.out.println("polygon is closed . "+polygon.getExteriorRing().isClosed());
                System.out.println("polygon is rectangle . "+polygon.getExteriorRing().isRectangle());
                System.out.println("polygon is simple . "+polygon.getBoundary().isSimple());*/
                List<Point2> polygonROICoordinates = new ArrayList<>();

                for (Coordinate polygonCoordinate : polygonCoordinates) {
                    polygonROICoordinates.add(new Point2(polygonCoordinate.x, polygonCoordinate.y));
                }

                polygonROIs.add(createROI(polygonROICoordinates, roi));

                // get the internal shapes of the polygon (i.e. holes)
                for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
                    polygonCoordinates = polygon.getInteriorRingN(j).getCoordinates();
                  /* System.out.println("polygon interne geometry type : "+polygon.getInteriorRingN(j).getGeometryType());
                    System.out.println("polygon interne is closed . "+polygon.getInteriorRingN(j).isClosed());
                    System.out.println("polygon interne is rectangle . "+polygon.getInteriorRingN(j).getBoundary().isRectangle());
                    System.out.println("polygon interne is simple . "+polygon.getInteriorRingN(j).getBoundary().isSimple());*/
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
            //TODO find a way to capture precisely if the plygon is an ellipse or not
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

    /**
     * Return the packed RGBA representation of the specified ARGB (packed) value.
     * <p>
     * This doesn't use the convenient method {@code makeRGBA()} as
     * the order in the method is confusing.
     * @param argb
     * @return rgba
     */
    private static int ARGBToRGBA(int argb) {
        int a =  (argb >> 24) & 0xff;
        int r =  (argb >> 16) & 0xff;
        int g =  (argb >> 8) & 0xff;
        int b =  argb & 0xff;
        return (r<<24) + (g<<16) + (b<<8) + a;
    }

//	/**
//	 * Return the packed ARGB representation of the specified RGBA (packed) value.
//	 * <p>
//	 * This method is similar to {@code makeRGBA()} but with packed RGBA input.
//	 * @param rgba
//	 * @return argb
//	 */
//	private static int RGBAToARGB(int rgba) {
//		int r =  (rgba >> 24) & 0xff;
//		int g =  (rgba >> 16) & 0xff;
//		int b =  (rgba >> 8) & 0xff;
//		int a =  rgba & 0xff;
//		return (a<<24) + (r<<16) + (g<<8) + b;
//	}

    public static abstract class OmeroRawShape {
        private int c = -1;
        private int z;
        private int t;
        private String type;
        private String text;
        private Boolean locked;
        private Integer fillColor;
        private Integer strokeColor;

        private String oldId = "-1:-1";

        private void setC(int c){ this.c = c;}
        private void setZ(int z){  this.z = z;}
        private void setT(int t){ this.t = t;}


        private PathObject createObject(Function<ROI, PathObject> fun) {
            var pathObject = fun.apply(createROI());
            initializeObject(pathObject);
            return pathObject;
        }

        abstract ROI createROI();

        protected PathObject createAnnotation() {
            return createObject(PathObjects::createAnnotationObject);
        }

        protected PathObject createDetection() {
            return createObject(PathObjects::createDetectionObject);
        }

        protected void initializeObject(PathObject pathObject) {
            if (text != null && !text.isBlank())
                pathObject.setName(text);
            if (strokeColor != null)
                pathObject.setColorRGB(strokeColor >> 8);
            if (locked != null)
                pathObject.setLocked(locked);
        }


        protected ImagePlane getPlane() {
            if (c >= 0)
                return ImagePlane.getPlaneWithChannel(c, z, t);
            else
                return ImagePlane.getPlane(z, t);
        }

        protected void setType(String type) {
            this.type = "http://www.openmicroscopy.org/Schemas/OME/2016-06#" + type;
        }

        protected void setText(String text) {
            this.text = text;
        }

        protected void setStrokeColor(Integer color) {
            this.strokeColor = color;
        }

        protected void setFillColor(Integer color) {
            this.fillColor = color;
        }

    }

    static class Rectangle extends OmeroRawShapes.OmeroRawShape {

        private double x;
        private double y;
        private double width;
        private double height;

        private Rectangle(double x, double y, double width, double height, int c, int z, int t) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;

            super.setType("Rectangle");
            super.setC(c);
            super.setZ(z);
            super.setT(t);

        }

        @Override
        ROI createROI() {
            logger.debug("Creating rectangle");
            return ROIs.createRectangleROI(x, y, width, height, getPlane());
        }
    }

    static class Ellipse extends OmeroRawShapes.OmeroRawShape {


        private double x;

        private double y;

        private double radiusX;

        private double radiusY;

        private Ellipse(double x, double y, double radiusX, double radiusY, int c, int z, int t) {
            this.x = x;
            this.y = y;
            this.radiusX = radiusX;
            this.radiusY = radiusY;

            super.setType("Ellipse");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating ellipse");
            return ROIs.createEllipseROI(x-radiusX, y-radiusY, radiusX*2, radiusY*2, getPlane());
        }
    }

    static class Line extends OmeroRawShapes.OmeroRawShape {

        private double x1;
        private double y1;
        private double x2;
        private double y2;

        private Line(double x1, double y1, double x2, double y2, int c, int z, int t) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;

            super.setType("Line");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating line");
            return ROIs.createLineROI(x1, y1, x2, y2, getPlane());
        }
    }

    static class Point extends OmeroRawShapes.OmeroRawShape {
        private double x;
        private double y;

        private Point(double x, double y, int c, int z, int t) {
            this.x = x;
            this.y = y;

            super.setType("Point");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating point");
            return ROIs.createPointsROI(x, y, getPlane());
        }
    }

    static class Polyline extends OmeroRawShapes.OmeroRawShape {

        private String pointString;

        private Polyline(String pointString, int c, int z, int t) {
            this.pointString = pointString;

            super.setType("Polyline");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating polyline");
            return ROIs.createPolylineROI(parseStringPoints(pointString), getPlane());
        }
    }

    static class Polygon extends OmeroRawShapes.OmeroRawShape {

        private String pointString;

        private Polygon(String pointString, int c, int z, int t) {
            this.pointString = pointString;

            super.setType("Polygon");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.debug("Creating polygon");
            return ROIs.createPolygonROI(parseStringPoints(pointString), getPlane());
        }
    }

    static class Label extends OmeroRawShapes.OmeroRawShape {

        private double x;
        private double y;

        private Label(double x, double y, int c, int z, int t) {
            this.x = x;
            this.y = y;

            super.setType("Label");
            super.setC(c);
            super.setZ(z);
            super.setT(t);
        }

        @Override
        ROI createROI() {
            logger.warn("Creating point (requested label shape is unsupported)");
            return ROIs.createPointsROI(x, y, getPlane());
        }
    }

    static class Mask extends OmeroRawShapes.OmeroRawShape {

        @Override
        ROI createROI() {
            throw new UnsupportedOperationException("Mask rois not yet supported!");
        }
    }

    /**
     * Parse the OMERO string representing points
     * @param pointsString
     * @return list of Point2
     */
    private static List<Point2> parseStringPoints(String pointsString) {
        List<Point2> points = new ArrayList<>();
        for (String p : pointsString.split(" ")) {
            String[] p2 = p.split(",");
            points.add(new Point2(Double.parseDouble(p2[0]), Double.parseDouble(p2[1])));
        }
        return points;
    }

    /**
     * Converts the specified list of {@code Point2}s into an OMERO-friendly string
     * @param points
     * @return string of points
     */
    private static String pointsToString(List<Point2> points) {
        return points.stream().map(e -> e.getX() + "," + e.getY()).collect(Collectors.joining (" "));


    }

}

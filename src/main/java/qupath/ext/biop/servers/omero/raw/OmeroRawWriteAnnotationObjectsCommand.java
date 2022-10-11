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

package qupath.ext.biop.servers.omero.raw;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import org.apache.commons.lang3.StringUtils;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

/**
 * Command to write path objects back to the OMERO server where the
 * current image is hosted.
 *
 * @author Melvin Gelbard
 *
 */
public class OmeroRawWriteAnnotationObjectsCommand implements Runnable {

    private final String title = "Select OMERO import options";

    private QuPathGUI qupath;

    OmeroRawWriteAnnotationObjectsCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        var viewer = qupath.getViewer();
        var server = viewer.getServer();

        // Check if OMERO server
        if (!(server instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for import options
        GridPane pane = new GridPane();

        CheckBox cbAnnotationsMap = new CheckBox("Annotation map");
        cbAnnotationsMap.setSelected(false);

        CheckBox cbDetectionsMap = new CheckBox("Detection map");
        cbDetectionsMap.setSelected(false);

        CheckBox cbDeleteRois = new CheckBox("Delete existing ROIs");
        cbDeleteRois.setSelected(false);

        int row = 0;
        pane.add(new Label("Import annotations with : "), 0, row++, 2, 1);
        pane.add(cbAnnotationsMap, 0, row++);
        pane.add(cbDetectionsMap, 0, row++);
        pane.add(cbDeleteRois, 0, ++row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean annotationMap = cbAnnotationsMap.isSelected();
        boolean deleteRois = cbDeleteRois.isSelected();
        boolean detectionMap = cbDetectionsMap.isSelected();

        // Check if at least one object was selected (and type)
       /* Collection<PathObject> selectedObjects = viewer.getHierarchy().getAnnotationObjects();//viewer.getAllSelectedObjects();
        Collection<PathObject> objs;
        if (selectedObjects.size() == 0) {
            // If no selection, get all annotation objects
            objs = viewer.getHierarchy().getAnnotationObjects();
            if (objs.size() == 0) {
                Dialogs.showErrorMessage(title, "No annotations to send!");
                return;
            }

            // Ask user if he/she wants to send all annotations instead
            var confirm = Dialogs.showConfirmDialog("Send annotations", String.format("No annotations are selected. Send all annotations instead? (%d %s)",
                    objs.size(),
                    (objs.size() == 1 ? "object" : "objects")));

            if (!confirm)
                return;
        } else {*/
            Collection<PathObject> objs = viewer.getHierarchy().getAnnotationObjects();
            //objs = selectedObjects;

            // Get detections amongst selection
            //List<PathObject> detections = objs.stream().filter(e -> e.isDetection()).collect(Collectors.toList());

            // Give warning and filter out detection objects
           /* if (detections.size() > 0) {
                Dialogs.showWarningNotification(title, String.format("Sending detection objects is not supported (%d %s)",
                        detections.size(),
                        (detections.size() == 1 ? "object" : "objects")));

                objs = objs.stream().filter(e -> !e.isDetection()).collect(Collectors.toList());
            }*/

            // Output message if no annotation object was found
            if (objs.size() == 0) {
                Dialogs.showErrorMessage(title, "No annotation objects to send!");
                return;
            }

            // Ask user if he/she wants to send all annotations instead
          /*  var confirm = Dialogs.showConfirmDialog("Send annotations", String.format("Send all annotations ? (%d %s)",
                    objs.size(),
                    (objs.size() == 1 ? "object" : "objects")));

            if (!confirm)
                return;*/
        //}

        // Confirm
        var omeroServer = (OmeroRawImageServer) server;
        URI uri = server.getURIs().iterator().next();
        String objectString = "object" + (objs.size() == 1 ? "" : "s");
        pane = new GridPane();
        PaneTools.addGridRow(pane, 0, 0, null, new Label(String.format("%d %s will be sent to:", objs.size(), objectString)));
        PaneTools.addGridRow(pane, 1, 0, null, new Label(uri.toString()));
        var confirm = Dialogs.showConfirmDialog("Send " + (objs.size() == 0 ? "all " : "") + objectString, pane);
        if (!confirm)
            return;


        // Write path object(s)
        try {
            // give to each pathObject a unique name
            objs.forEach(pathObject -> pathObject.setName(""+ (new Date()).getTime() + pathObject.hashCode()));

            // send annotations to OMERO
            OmeroRawTools.writePathObjects(objs, omeroServer, deleteRois);
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully written to OMERO server",
                    objs.size(),
                    objectString,
                    (objs.size() == 1 ? "was" : "were")));

            if(annotationMap) {
                // send annotation measurements
                ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
                ob.setImageData(qupath.getImageData(), objs);
                OmeroRawTools.writeMeasurementTableData(objs, ob, qupath.getProject().getName().split("/")[0], omeroServer);
            }
            if(detectionMap){
                // get detection objects
                ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
                Collection<PathObject> detections = viewer.getHierarchy().getDetectionObjects();
                //detections.addAll(viewer.getHierarchy().getCellObjects());

                // send detection measurement map
                if(detections.size() > 0) {
                    ob.setImageData(qupath.getImageData(), detections);
                    OmeroRawTools.writeMeasurementTableData(detections, ob, qupath.getProject().getName().split("/")[0], omeroServer);
                }
                else Dialogs.showErrorMessage(title, "No detection objects , cannot send detection map!");
            }

            // remove the name to not interfere with QuPath ROI display.
            objs.forEach(pathObject -> pathObject.setName(null));

            if(detectionMap || annotationMap)
                Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d measurement %s %s successfully written to OMERO server",
                        detectionMap && annotationMap ? 2 : 1,
                        detectionMap && annotationMap ? "maps": "map",
                        detectionMap && annotationMap ? "was" : "were"));

        } catch (IOException ex) {
            objs.forEach(pathObject -> pathObject.setName(null));
            Dialogs.showErrorNotification("Could not send " + objectString, ex.getLocalizedMessage());
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e) {
            objs.forEach(pathObject -> pathObject.setName(null));
            throw new RuntimeException(e);
        }
    }
}

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

import java.net.URI;
import java.util.Collection;
import java.util.Date;

import javafx.scene.control.CheckBox;
import org.apache.commons.lang3.StringUtils;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.objects.PathObject;


/**
 * Command to write path objects back to the OMERO server where the
 * current image is hosted.
 *
 * @author Melvin Gelbard
 *
 */
public class OmeroRawWriteAnnotationObjectsCommand implements Runnable {

    private final String title = "Sending annotations";

    private final QuPathGUI qupath;

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

        CheckBox cbAnnotationsMap = new CheckBox("Annotation measurements");
        cbAnnotationsMap.setSelected(false);

        CheckBox cbDetectionsMap = new CheckBox("Detection measurements");
        cbDetectionsMap.setSelected(false);

        CheckBox cbDeleteRois = new CheckBox("Delete existing annotations on OMERO");
        cbDeleteRois.setSelected(false);

        int row = 0;
        pane.add(new Label("Send all annotations with : "), 0, row++, 2, 1);
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

        Collection<PathObject> objs = viewer.getHierarchy().getAnnotationObjects();

        // Output message if no annotation object was found
        if (objs.size() == 0) {
            Dialogs.showErrorMessage(title, "No annotation objects to send!");
            return;
        }

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

        // send annotations to OMERO
        boolean hasBeenSaved = OmeroRawScripting.sendPathObjectsToOmero(omeroServer, objs, deleteRois);
        if(hasBeenSaved)
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully written to OMERO server",
                    objs.size(),
                    objectString,
                    (objs.size() == 1 ? "was" : "were")));
        else {
            Dialogs.showErrorMessage("Sending annotations", "Cannot send annotations to OMERO. Please look at the log console to know more (View->Show log).");
            return;
        }

        if(annotationMap) {
            // send table to OMERO
            OmeroRawScripting.sendAnnotationMeasurementTable(objs, omeroServer, qupath.getImageData());

            // send the corresponding csv file
            OmeroRawScripting.sendAnnotationMeasurementTableAsCSV(objs, omeroServer, qupath.getImageData());
        }
        if(detectionMap){
            // get detection objects
            Collection<PathObject> detections = viewer.getHierarchy().getDetectionObjects();

            // send detection measurement map
            if(detections.size() > 0) {
                // send table to OMERO
                OmeroRawScripting.sendDetectionMeasurementTable(detections, omeroServer, qupath.getImageData());

                // send the corresponding csv file
                OmeroRawScripting.sendDetectionMeasurementTableAsCSV(detections, omeroServer, qupath.getImageData());
            }
            else Dialogs.showErrorMessage(title, "No detection objects , cannot send detection map!");
        }

        if(detectionMap || annotationMap)
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d measurement maps were successfully sent to OMERO server",
                    detectionMap && annotationMap ? 4 : 2));

    }
}

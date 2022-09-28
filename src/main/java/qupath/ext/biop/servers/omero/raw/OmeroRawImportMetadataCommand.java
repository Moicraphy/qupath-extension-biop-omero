package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.MapAnnotationData;
import omero.model.NamedValue;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class OmeroRawImportMetadataCommand implements Runnable{

    private final String title = "Import KeyValues from OMERO";
    private QuPathGUI qupath;
    public OmeroRawImportMetadataCommand(QuPathGUI qupath)  {
        this.qupath = qupath;
    }

    @Override
    public void run() {

        // get the current image
        ImageServer<BufferedImage> imageServer = this.qupath.getViewer().getServer();

        // Check if OMERO server
        if (!(imageServer instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for import options
        GridPane pane = new GridPane();
        final ToggleGroup group = new ToggleGroup();

        RadioButton rbKeepMetadata = new RadioButton("Add to existing Key-Values");
        rbKeepMetadata.setToggleGroup(group);
        rbKeepMetadata.setSelected(true);

        RadioButton rbRemoveMetadata = new RadioButton("Replace existing Key-Values");
        rbRemoveMetadata.setToggleGroup(group);

        int row = 0;
        pane.add(new Label("Select import options"), 0, row++, 2, 1);
        pane.add(rbKeepMetadata, 0, row++);
        pane.add(rbRemoveMetadata, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean keepMetadata = rbKeepMetadata.isSelected();

        // read keyValue from QuPath
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());

        // delete metadata
        if(!keepMetadata)
            entry.clearMetadata();


        // get key values in OMERO
        List<MapAnnotationData> annotationData;
        try {
            annotationData = OmeroRawTools.readKeyValues((OmeroRawImageServer) imageServer);
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e) {
            throw new RuntimeException(e);
        }

        if(annotationData.isEmpty()) {
            Dialogs.showWarningNotification(title, "The current image does not have any KeyValues on OMERO");
            return;
        }

        List<NamedValue> keyValues = new ArrayList<>();
        annotationData.forEach(annotation->{
            keyValues.addAll((List<NamedValue>)annotation.getContent());
        });

        Map<String, String> currentKV = entry.getMetadataMap();
        AtomicInteger nExistingKV = new AtomicInteger();

        keyValues.forEach(pair->{
            boolean existingKeyValue = false;
            ArrayList<String> existingKeys = new ArrayList<>(currentKV.keySet());

            // check if the key-value already exists in QuPath
            for (String existingKey : existingKeys) {
                if (pair.name.equals(existingKey) && pair.value.equals(currentKV.get(existingKey))) {
                    existingKeyValue = true;
                    break;
                }
            }

            // add key values to qupath metadata
            if(!existingKeyValue)
                entry.putMetadataValue(pair.name, pair.value);
            else
                nExistingKV.getAndIncrement();
        });

        // inform user if some key-values already exists in QuPath
        if(nExistingKV.get() > 0)
            Dialogs.showWarningNotification(title, String.format("%d %s already %s in QuPath",nExistingKV.get(),
                    (nExistingKV.get() == 1 ? "key-value" : "key-values"),
                    (nExistingKV.get() == 1 ? "exists" : "exist")));
    }
}

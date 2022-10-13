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

        RadioButton rbKeepMetadata = new RadioButton("Keep existing and add new");
        rbKeepMetadata.setToggleGroup(group);

        RadioButton rbReplaceMetadata = new RadioButton("Replace existing and add new");
        rbReplaceMetadata.setToggleGroup(group);
        rbReplaceMetadata.setSelected(true);

        RadioButton rbDeleteMetadata = new RadioButton("Delete all and add new");
        rbDeleteMetadata.setToggleGroup(group);

        int row = 0;
        pane.add(new Label("Select import options"), 0, row++, 2, 1);
        pane.add(rbKeepMetadata, 0, row++);
        pane.add(rbReplaceMetadata, 0, row++);
        pane.add(rbDeleteMetadata, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean keepMetadata = rbKeepMetadata.isSelected();
        boolean replaceMetadata = rbReplaceMetadata.isSelected();
        boolean deleteMetadata = rbDeleteMetadata.isSelected();

        // read keyValue from QuPath
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());


        // get key values in OMERO
        List<MapAnnotationData> annotationData;
        boolean uniqueOmeroKeys;
        try {
            annotationData = OmeroRawTools.readKeyValues((OmeroRawImageServer) imageServer);
            uniqueOmeroKeys = OmeroRawTools.checkUniqueKeyInAnnotationMap(annotationData);
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e) {
            throw new RuntimeException(e);
        }

        if (annotationData.isEmpty()) {
            Dialogs.showWarningNotification(title, "The current image does not have any KeyValues on OMERO");
            return;
        }

        if (!uniqueOmeroKeys) {
            Dialogs.showErrorMessage(title, "There is at least two identical keys on OMERO. Please, make all keys unique. Metadata has not been modified.");
            return;
        }

        List<NamedValue> keyValues = new ArrayList<>();
        annotationData.forEach(annotation -> {
            keyValues.addAll((List<NamedValue>) annotation.getContent());
        });

        int nQuPathKV = entry.getMetadataKeys().size();
        // delete metadata
        if (deleteMetadata)
            entry.clearMetadata();


        Map<String, String> currentKV = entry.getMetadataMap();
        AtomicInteger nExistingKV = new AtomicInteger();

        keyValues.forEach(pair -> {
            boolean existingKeyValue = false;
            ArrayList<String> existingKeys = new ArrayList<>(currentKV.keySet());

            // check if the key-value already exists in QuPath
            for (String existingKey : existingKeys) {
                if (pair.name.equals(existingKey)) {
                    existingKeyValue = true;
                    nExistingKV.getAndIncrement();
                    break;
                }
            }

            // add key values to qupath metadata
            if (!existingKeyValue || replaceMetadata)
                entry.putMetadataValue(pair.name, pair.value);
        });

        // inform user if some key-values already exists in QuPath
        if (keepMetadata)
            Dialogs.showInfoNotification(title, String.format("Keep %d %s and add %d new %s", nQuPathKV,
                    (nQuPathKV <= 1 ? "key-value" : "key-values"),
                    keyValues.size()-nExistingKV.get(),
                    (keyValues.size()-nExistingKV.get() <= 1 ? "key-value" : "key-values")));
        if(replaceMetadata)
            Dialogs.showInfoNotification(title, String.format("Update %d %s and add %d new %s", nExistingKV.get(),
                    (nExistingKV.get() <= 1 ? "key-value" : "key-values"),
                    keyValues.size()-nExistingKV.get(),
                    (keyValues.size()-nExistingKV.get() <= 1 ? "key-value" : "key-values")));
        if(deleteMetadata)
            Dialogs.showInfoNotification(title, String.format("Delete %d previous key-values and add %d new %s", nQuPathKV, keyValues.size(),
                    (keyValues.size() <= 1 ? "key-value" : "key-values")));
    }

}

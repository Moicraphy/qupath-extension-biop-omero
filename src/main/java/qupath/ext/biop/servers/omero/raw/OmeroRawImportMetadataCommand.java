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

        // inform user if some key-values already exists in QuPath
        if (keepMetadata) {
            // get the initial number of key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValues((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nQuPathKVAfterAdding = entry.getMetadataKeys().size();
            int nNewKV = nQuPathKVAfterAdding - nExistingKV;

            Dialogs.showInfoNotification(title, String.format("Keep %d %s and add %d new %s", nExistingKV,
                    (nExistingKV <= 1 ? "key-value" : "key-values"),
                    nNewKV,
                    (nNewKV <= 1 ? "key-value" : "key-values")));
        }
        if(replaceMetadata) {
            // get the initial number of key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValuesAndUpdateMetadata((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nQuPathKVAfterAdding = entry.getMetadataKeys().size();
            int nNewKV = nQuPathKVAfterAdding - nExistingKV;

            Dialogs.showInfoNotification(title, String.format("Update %d %s and add %d new %s", nExistingKV,
                    (nExistingKV <= 1 ? "key-value" : "key-values"),
                    nNewKV,
                    (nNewKV <= 1 ? "key-value" : "key-values")));
        }
        if(deleteMetadata) {
            // get the number of new key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValuesAndDeleteMetadata((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nNewKV = entry.getMetadataKeys().size();

            Dialogs.showInfoNotification(title, String.format("Delete %d previous key-values and add %d new %s", nExistingKV, nNewKV,
                    (nNewKV <= 1 ? "key-value" : "key-values")));
        }
    }

}

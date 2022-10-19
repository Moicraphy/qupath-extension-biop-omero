package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.apache.commons.lang3.StringUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;


/**
 * Command to import QuPath metatdata to OMERO server where the
 * current image is hosted. Metadata are added as a new key-value pair.
 *
 * @author Rémy Dornier (parts of the code are taken from {@link OmeroRawWriteAnnotationObjectsCommand}.
 *
 */
public class OmeroRawWriteMetadataCommand  implements Runnable{

    private final String title = "Send metadata";
    private QuPathGUI qupath;
    public OmeroRawWriteMetadataCommand(QuPathGUI qupath)  {
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
        boolean replaceMetadata = rbReplaceMetadata.isSelected();
        boolean deleteMetadata = rbDeleteMetadata.isSelected();
        boolean keepMetadata = rbKeepMetadata.isSelected();

        // get keys
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());
        Collection<String> keys = entry.getMetadataKeys();

        if (keys.size() > 0) {
            // Ask user if he/she wants to send all annotations
            boolean confirm = Dialogs.showConfirmDialog(title, String.format("Do you want to send all metadata as key-values ? (%d %s)",
                    keys.size(),
                    (keys.size() == 1 ? "object" : "objects")));

            if (!confirm)
                return;
        }else{
            Dialogs.showWarningNotification(title, "The current image does not contain any metadata");
            return;
        }

        // build a map of key and values from metadata
        Map<String,String> keyValues = new HashMap<>();
        for (String key : keys) {
            keyValues.put(key, entry.getMetadataValue(key));
        }

        String objectString = "key-value" + (keys.size() == 1 ? "" : "s");
        boolean wasSaved = true;

        // send metadata to OMERO
        if(deleteMetadata)
            wasSaved = OmeroRawScripting.saveMetadataOnOmeroAndDeleteKeyValues(keyValues,(OmeroRawImageServer)imageServer);
        if(keepMetadata)
            wasSaved = OmeroRawScripting.saveMetadataOnOmero(keyValues,(OmeroRawImageServer)imageServer);
        if(replaceMetadata)
            wasSaved = OmeroRawScripting.saveMetadataOnOmeroAndUpdateKeyValues(keyValues,(OmeroRawImageServer)imageServer);

        if(wasSaved)
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully written to OMERO server",
                    keys.size(),
                    objectString,
                    (keys.size() == 1 ? "was" : "were")));
    }
}

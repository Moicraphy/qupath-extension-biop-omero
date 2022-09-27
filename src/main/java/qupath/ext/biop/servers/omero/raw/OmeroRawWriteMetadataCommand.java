package qupath.ext.biop.servers.omero.raw;


import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import org.apache.commons.lang3.StringUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ExecutionException;


/**
 * Command to import QuPath metatdata to OMERO server where the
 * current image is hosted. Metadata are added as a new key-value pair.
 *
 * @author Rémy Dornier
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

        // send metadata to OMERO
        String objectString = "key-value" + (keys.size() == 1 ? "" : "s");
        try {
            OmeroRawTools.writeMetadata(keyValues, (OmeroRawImageServer)imageServer);
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully written to OMERO server",
                    keys.size(),
                    objectString,
                    (keys.size() == 1 ? "was" : "were")));
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("Could not send " + objectString, e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
    }
}

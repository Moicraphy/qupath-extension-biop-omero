package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectReader;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;

/**
 * Command to import ROIs from OMERO server where the
 * current image is hosted.
 *
 * @author Rémy Dornier
 *
 */
public class OmeroRawImportAnnotationObjectsCommand implements Runnable{

    private final String title = "Import objects from OMERO";
    private final QuPathGUI qupath;
    public OmeroRawImportAnnotationObjectsCommand(QuPathGUI qupath)  {
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

        CheckBox cbRemoveAnnotations = new CheckBox("Delete current annotations");
        cbRemoveAnnotations.setSelected(true);

        CheckBox cbRemoveDetections = new CheckBox("Delete current detections");
        cbRemoveDetections.setSelected(true);

        int row = 0;
        pane.add(new Label("Select import options"), 0, row++, 2, 1);
        pane.add(cbRemoveAnnotations, 0, row++);
        pane.add(cbRemoveDetections, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean removeDetections = cbRemoveDetections.isSelected();
        boolean removeAnnotations = cbRemoveAnnotations.isSelected();

        // read ROIs from OMERO
        Collection<PathObject> roiFromOmero;
        try {
            roiFromOmero = ((PathObjectReader) imageServer).readPathObjects();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // get the current hierarchy
        PathObjectHierarchy hierarchy = this.qupath.getViewer().getImageData().getHierarchy();

        // remove current annotations
        if(removeAnnotations)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(),true);

        // remove current detections
        if(removeDetections)
            hierarchy.removeObjects(hierarchy.getDetectionObjects(), false);

        // add rois from OMERO
        if(!roiFromOmero.isEmpty()) {
            hierarchy.addObjects(roiFromOmero);
            hierarchy.resolveHierarchy();
        }
        else{
            Dialogs.showWarningNotification(title, "The current image does not have any ROIs on OMERO");
        }
    }
}

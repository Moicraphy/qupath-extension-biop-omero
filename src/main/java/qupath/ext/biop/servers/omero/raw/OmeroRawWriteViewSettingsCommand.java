package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

public class OmeroRawWriteViewSettingsCommand implements Runnable {
    private final String title = "Sending view settings";
    private QuPathGUI qupath;
    public OmeroRawWriteViewSettingsCommand(QuPathGUI qupath)  {
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

        CheckBox cbChannelNames = new CheckBox("Channel names");
        cbChannelNames.setSelected(false);

        CheckBox cbChannelDisplayRange = new CheckBox("Channel display ranges");
        cbChannelDisplayRange.setSelected(false);

        CheckBox cbChannelColor = new CheckBox("Channel colors");
        cbChannelColor.setSelected(false);

        int row = 0;
        pane.add(new Label("Select view settings options"), 0, row++, 2, 1);
        pane.add(cbChannelNames, 0, row++);
        pane.add(cbChannelDisplayRange, 0, row++);
        pane.add(cbChannelColor, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean channelNames = cbChannelNames.isSelected();
        boolean channelDisplayRange = cbChannelDisplayRange.isSelected();
        boolean channelColor = cbChannelColor.isSelected();

        boolean wasSaved = true;

        // send display settings to OMERO
        if(channelDisplayRange)
            wasSaved = OmeroRawScripting.sendChannelsDisplayRangeToOmero((OmeroRawImageServer)imageServer);
        if(channelColor)
            wasSaved = OmeroRawScripting.sendChannelsColorToOmero((OmeroRawImageServer)imageServer);
        if(channelNames)
            wasSaved = OmeroRawScripting.sendChannelsNameToOmero((OmeroRawImageServer)imageServer);

        if(wasSaved)
            Dialogs.showInfoNotification(" Image update successfully", "View settings have been successfully updated");
    }
}

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

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.PaneTools;

/**
 * Command to manually manage OMERO web clients. This offers the possibility to log in/off
 * and 'forget' OMERO web clients.
 *
 * @author Melvin Gelbard
 */
public class OmeroRawClientsCommand implements Runnable {

    final private static Logger logger = LoggerFactory.getLogger(OmeroRawClientsCommand.class);

    private final QuPathGUI qupath;
    private Stage dialog;
    private final ObservableSet<ServerInfo> clientsDisplayed;
    private ExecutorService executor;

    // GUI
    private GridPane mainPane;

    OmeroRawClientsCommand(QuPathGUI qupath) {
        this.qupath = qupath;
        this.clientsDisplayed = FXCollections.observableSet();
    }

    @Override
    public void run() {
        if (dialog == null) {
            // Get connection status of each imageServer in separate thread
            executor = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("OMERO-server-status", true));

            dialog = new Stage();
            mainPane = new GridPane();
            mainPane.setMinWidth(250);
            mainPane.setMinHeight(50);
            mainPane.setPadding(new Insets(0.0, 0.5, 5, 0.5));

            // If a change is detected in the clients list, refresh pane
            OmeroRawClients.getAllClients().addListener(new ListChangeListener<OmeroRawClient>() {
                @Override
                public void onChanged(Change<? extends OmeroRawClient> c) {
                    if (dialog == null)
                        return;

                    // If 'import-project' thread ('Open URI..'), 'Not on FX appl. thread' Exception can be thrown
                    Platform.runLater(() -> {
                        refreshServerGrid();
                        dialog.getScene().getWindow().sizeToScene();
                    });
                }
            });

            refreshServerGrid();

            mainPane.setVgap(10.0);
            dialog.sizeToScene();
            dialog.setResizable(false);
            dialog.setTitle("OMERO raw clients");
            dialog.setScene(new Scene(mainPane));
            dialog.setOnCloseRequest(e -> dialog = null);
            QuPathGUI qupath2 = QuPathGUI.getInstance();
            if (qupath2 != null)
                dialog.initOwner(qupath2.getStage());
        } else
            dialog.requestFocus();

        dialog.sizeToScene();
        dialog.show();
    }


    private void refreshServerGrid() {
        mainPane.getChildren().clear();
        var allClients = OmeroRawClients.getAllClients();

        for (var client: allClients) {
            clientsDisplayed.removeIf(serverInfo -> serverInfo.client.getGateway().equals(client.getGateway()));
        }

        for (var client: allClients) {
            // If new client is not displayed, add it to the set
            if (clientsDisplayed.stream().noneMatch(e -> e.client.equals(client)))
                clientsDisplayed.add(new ServerInfo(client));
        }

        int row = 0;
        // Using iterator to avoid ConcurrentModificationExceptions
        for (var i = clientsDisplayed.iterator(); i.hasNext();) {
            var serverInfo = i.next();

            // If the client list does not contain this client, remove from set
            if (!allClients.contains(serverInfo.client)) {
                i.remove();
                continue;
            }
            mainPane.addRow(row++, serverInfo.getPane());
        }

        // If empty, display 'No OMERO clients' label
        if (clientsDisplayed.isEmpty()) {
            Platform.runLater(() -> {
                mainPane.setAlignment(Pos.CENTER);
                mainPane.add(new Label("No OMERO clients"), 0, 0);
            });
        }
    }


    /**
     * Class to keep info about an OMERO server for display.
     * The point here is to keep track of the bindings with {@code OmeroRawClients}
     * and to avoid having to recreate panes after each update.
     * <p>
     * Each instance has a {@code GridPane} which is created <b>once</b>. Within this pane, the
     * {@code titledPane} is continuously updated according to the URI list of the
     * {@code client} (each addition/removal recreate the title pane content).
     *
     */
    class ServerInfo {

        private final OmeroRawClient client;
        private final GridPane pane;

        private final IntegerProperty nImages;

        private ServerInfo(OmeroRawClient client) {
            this.client = client;
            this.nImages = new SimpleIntegerProperty(0);
            this.pane = createServerPane();
        }

        private GridPane getPane() {
            return pane;
        }

        private GridPane createServerPane() {
            // The username should be the same for all images in the server
            String username = client.getUsername();
            GridPane gridPane = new GridPane();
            BorderPane infoPane = new BorderPane();
            GridPane actionPane = new GridPane();

            URI uri = client.getServerURI();
            Label userLabel = new Label();
            userLabel.textProperty().bind(Bindings
                    .when(client.usernameProperty().isNotEmpty())
                    .then(Bindings.concat(uri.toString(), " (", client.usernameProperty(), ")"))
                    .otherwise(Bindings.concat(uri.toString())));

            // Bind state node
            userLabel.graphicProperty().bind(Bindings.createObjectBinding(() -> {
                if (client.getUsername().isEmpty())
                    return OmeroRawTools.createStateNode(client.checkIfLoggedIn());
                else
                    return OmeroRawTools.createStateNode(client.logProperty().get());
            }, client.usernameProperty()));

            // Make it appear on the right of the server's URI
            userLabel.setContentDisplay(ContentDisplay.RIGHT);

            nImages.bind(Bindings.size(client.getURIs()));

            TitledPane tp = new TitledPane();
            tp.textProperty().bind(Bindings.concat(nImages, " image(s)"));
            tp.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            tp.setExpanded(false);
            tp.heightProperty().addListener((v, o, n) -> Platform.runLater(() -> dialog.sizeToScene()));
            tp.widthProperty().addListener((v, o, n) -> Platform.runLater(() -> dialog.sizeToScene()));

            // If the login status or the client's username has changed or a new image is opened, recreate the titlePane content
            var contentBinding = Bindings.createObjectBinding(() -> createTitledPaneContent(client), client.usernameProperty(), client.getURIs());
            tp.contentProperty().bind(contentBinding);
            tp.collapsibleProperty().bind(nImages.greaterThan(0));

            Platform.runLater(() -> {
                if (dialog == null)
                    return;
                try {
                    // These 2 next lines help prevent NPE
                    tp.applyCss();
                    tp.layout();
                    tp.setStyle(".title {}");
                    tp.lookup(".title").setStyle("-fx-background-color: transparent");
                    tp.lookup(".title").setEffect(null);
                    tp.lookup(".content").setStyle("-fx-border-color: null");
                } catch (Exception e) {
                    logger.error("Error setting CSS style: {}", e.getLocalizedMessage());
                }
            });
            infoPane.setBottom(tp);

            Button connectionBtn = new Button();
            // Bind button's text properly
            connectionBtn.textProperty().bind(Bindings.createStringBinding(() -> {
                if (client.isLoggedIn()) {
                    if (client.getUsername().isEmpty())
                        return "Log in";
                    return "Log out";
                }
                return "Log in";
            },  client.logProperty(), client.usernameProperty()));

            Button removeBtn = new Button("Remove");
            PaneTools.addGridRow(actionPane, 0, 0, null, connectionBtn, removeBtn);
            infoPane.setLeft(userLabel);
            infoPane.setRight(actionPane);

            connectionBtn.setOnAction(e -> {
                if (connectionBtn.getText().equals("Log in")) {
                    boolean success = true;
                    success = client.logIn();
                    if (!success)
                        Dialogs.showErrorMessage("Log in to OMERO server", "Could not log in to server. Check the log for more info.");
                } else {
                    // Check again the state, in case it wasn't refreshed in time
                    //if (client.isLoggedIn()) {     // commented because of sudo connection
                        if (OmeroRawExtension.getOpenedRawBrowsers().containsKey(client)) {
                            var confirm = Dialogs.showConfirmDialog("Log out", "A browser for this OMERO server is currently opened and will be closed when logging out. Continue?");
                            if (confirm)
                                client.logOut();
                        } else
                            client.logOut();
                   //}
                }
            });

            removeBtn.setOnMouseClicked(e -> {
                // Check if the webclient to delete is currently used in any viewer
                if (qupath.getViewers().stream().anyMatch(viewer -> {
                    if (viewer.getServer() == null)
                        return false;
                    URI viewerURI = viewer.getServer().getURIs().iterator().next();
                    return client.getURIs().contains(viewerURI);
                })) {
                    Dialogs.showMessageDialog("Remove OMERO client", "You need to close images from this server in the viewer first!");
                    return;
                }

                var confirm = Dialogs.showConfirmDialog("Remove client", "This client will be removed from the list of active OMERO clients.");
                if (!confirm)
                    return;

                if (!username.isEmpty() && client.isLoggedIn())
                    client.logOut();
                OmeroRawClients.removeClient(client);
            });
            removeBtn.disableProperty().bind(client.logProperty().and(client.usernameProperty().isNotEmpty()));

            PaneTools.addGridRow(gridPane, 0, 0, null, infoPane);
            PaneTools.addGridRow(gridPane, 1, 0, null, tp);

            GridPane.setHgrow(gridPane, Priority.ALWAYS);
            GridPane.setHgrow(tp, Priority.ALWAYS);
            actionPane.setHgap(5.0);
            gridPane.setPadding(new Insets(5, 5, 5, 5));

            gridPane.setStyle("-fx-border-color: black;");
            return gridPane;
        }


        private GridPane createTitledPaneContent(OmeroRawClient client2) {
            GridPane gp = new GridPane();
            for (URI imageUri: client2.getURIs()) {
                // To save time, check the imageServers' status in other threads and update the pane later
                ProgressIndicator pi = new ProgressIndicator();
                pi.setPrefSize(15, 15);
                Label imageServerName = new Label("../" + imageUri.getQuery(), pi);
                imageServerName.setContentDisplay(ContentDisplay.RIGHT);
                PaneTools.addGridRow(gp, gp.getRowCount(), 0, null, imageServerName);

                executor.submit(() -> {
                    try {
                        final boolean canAccessImage = OmeroRawClient.canBeAccessed(imageUri, OmeroRawObjects.OmeroRawObjectType.IMAGE);
                        String tooltip = (client2.isLoggedIn() && !canAccessImage) ? "Unreachable image (access not permitted)" : imageUri.toString();
                        Platform.runLater(() -> {
                            imageServerName.setTooltip(new Tooltip(tooltip));
                            imageServerName.setGraphic(OmeroRawTools.createStateNode(canAccessImage));
                        });
                    } catch (ConnectException ex) {
                        logger.warn(ex.getLocalizedMessage());
                        Platform.runLater(() -> {
                            imageServerName.setTooltip(new Tooltip("Unreachable image"));
                            imageServerName.setGraphic(OmeroRawTools.createStateNode(false));
                        });
                    }
                });
            }
            gp.setHgap(5.0);
            return gp;
        }
    }
}

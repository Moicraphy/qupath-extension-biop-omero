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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import omero.RLong;
import omero.ServerError;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.*;
import omero.gateway.model.*;
import omero.model.*;
import omero.model.Image;
import omero.model.Label;
import omero.model.Point;
import omero.model.Polygon;
import omero.model.Rectangle;
import omero.model.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javafx.scene.Node;
import qupath.lib.color.ColorToolsAwt;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;

import static omero.rtypes.rint;


/**
 * Static helper methods related to OMERORawImageServer.
 *
 * @author Melvin Gelbard
 *
 */
public final class OmeroRawTools {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawTools.class);

    /**
     * Patterns to parse image URIs (for IDs)
     */
    private final static Pattern patternOldViewer = Pattern.compile("/webgateway/img_detail/(\\d+)");
    private final static Pattern patternNewViewer = Pattern.compile("images=(\\d+)");
    private final static Pattern patternWebViewer= Pattern.compile("/webclient/img_detail/(\\d+)");
    private final static Pattern patternLinkImage = Pattern.compile("show=image-(\\d+)");
    private final static Pattern patternImgDetail = Pattern.compile("img_detail/(\\d+)");
    private final static String noImageThumbnail = "NoImage256.png";
    private final static Pattern[] imagePatterns = new Pattern[] {patternOldViewer, patternNewViewer, patternWebViewer, patternImgDetail, patternLinkImage};

    /**
     * Pattern to recognize the OMERO type of an URI (i.e. Project, Dataset, Image, ..)
     */
    private final static Pattern patternType = Pattern.compile("show=(\\w+-)");

    /**
     * Patterns to parse Project and Dataset IDs ('link URI').
     */
    private final static Pattern patternLinkProject = Pattern.compile("show=project-(\\d+)");
    private final static Pattern patternLinkDataset = Pattern.compile("show=dataset-(\\d+)");

    private final static String um = GeneralTools.micrometerSymbol();

    /**
     * Suppress default constructor for non-instantiability
     */
    private OmeroRawTools() {
        throw new AssertionError();
    }

    /**
     * Retrieve a user on OMERO based on its id
     *
     * @param client
     * @param userId
     * @param username
     * @return
     */
    public static Experimenter getOmeroUser(OmeroRawClient client, long userId, String username){
        try {
            return client.getGateway().getAdminService(client.getContext()).getExperimenter(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user "+username +" ; id: "+userId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve all users within the group on OMERO
     *
     * @param client
     * @param groupId
     * @return
     */
    public static List<Experimenter> getOmeroUsersInGroup(OmeroRawClient client, long groupId){
        try {
            return client.getGateway().getAdminService(client.getContext()).containedExperimenters(groupId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO users in group "+groupId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve a group on OMERO based on its id
     *
     * @param client
     * @param groupId
     * @return
     */
    public static ExperimenterGroup getOmeroGroup(OmeroRawClient client, long groupId, String groupName){
        try {
            return client.getGateway().getAdminService(client.getContext()).getGroup(groupId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO group "+groupName +" ; id: "+groupId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * get all the groups where the current user is member of
     *
     * @return
     */
    public static List<ExperimenterGroup> getUserOmeroGroups(OmeroRawClient client, long userId) {
        try {
            return client.getGateway().getAdminService(client.getContext()).containedGroups(userId);
        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorMessage("OMERO admin","Cannot retrieve OMERO groups for user "+userId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * get all the groups on OMERO server (only for admin people)
     *
     * @return
     */
    public static List<ExperimenterGroup> getAllOmeroGroups(OmeroRawClient client) {
        try {
            if(client.getIsAdmin())
                return client.getGateway().getAdminService(client.getContext()).lookupGroups();
            else {
                Dialogs.showWarningNotification("OMERO admin", "You are not allowed to see all OMERO groups. Only available groups for you are loaded");
                return getUserOmeroGroups(client, client.getLoggedInUser().getId().getValue());
            }
        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorMessage("OMERO admin", "Cannot retrieve all OMERO groups");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * return the default {@link ExperimenterGroup} object of a certain user
     *
     * @param client
     * @return
     */
    public static ExperimenterGroup getDefaultOmeroGroup(OmeroRawClient client, long userId) {
        try {
            return client.getGateway().getAdminService(client.getContext()).getDefaultGroup(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read the default OMERO group for the user "+userId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    public static long getGroupIdFromImageId(OmeroRawClient client, long imageId){
        try {
            // request an imageData object for the image id by searching in all groups on OMERO
            // take care if the user is admin or not
            ImageData img = (ImageData) client.getGateway().getFacility(BrowseFacility.class).findObject(client.getContext(), "ImageData", imageId, true);
            return img.getGroupId();

        } catch (DSOutOfServiceException | NoSuchElementException | ExecutionException | DSAccessException e) {
            Dialogs.showErrorNotification("Get group id","Cannot retrieved group id from image "+imageId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return -1;
        }
    }


    /**
     * get all the orphaned datasets from the OMERO server for a certain user.
     *
     * @param client the client {@link OmeroRawClient} object
     * @return list of orphaned datasets
     */
    public static Collection<DatasetData> readOmeroOrphanedDatasetsPerOwner(OmeroRawClient client, long userId) {
        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getGateway().getQueryService(client.getContext()).findAllByQuery("select dataset from Dataset as dataset " +
                    "join fetch dataset.details.owner as o " +
                    "where o.id = "+ userId +
                    "and not exists (select obl from " +
                    "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            return client.getGateway().getFacility(BrowseFacility.class).getDatasets(client.getContext(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets for user "+userId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset of the user "+userId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * get all the orphaned datasets from the OMERO server for the current group (contained in the security context of the current client).
     *
     * @param client
     * @return
     */
    public static Collection<DatasetData> readOmeroOrphanedDatasets(OmeroRawClient client)  {
        Collection<DatasetData> orphanedDatasets;

        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getGateway().getQueryService(client.getContext()).findAllByQuery("select dataset from Dataset as dataset " +
                            "left outer join fetch dataset.details.owner " +
                            "where not exists (select obl from " +
                            "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            orphanedDatasets = client.getGateway().getFacility(BrowseFacility.class).getDatasets(client.getContext(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets");
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset");
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        return orphanedDatasets;
    }


    /**
     * Get all the orphaned {@code OmeroRawObject}s of type Image from the server.
     *
     * @param client the client {@code OmeroRawClient} object
     * @return list of orphaned images
     */
    public static Collection<ImageData> readOmeroOrphanedImagesPerUser(OmeroRawClient client, long userId) {
        try {
            return client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getContext(), userId);
        } catch (ExecutionException e) {
            Dialogs.showErrorMessage("Orphaned images","Cannot retrieved orphaned images for user "+userId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }



    /**
     * read rendering settings of an image to get access to channel information
     * Code partially copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getChannelColor} method
     *
     * @param client
     * @param imageId
     * @return
     */
    public static RenderingDef readOmeroRenderingSettings(OmeroRawClient client, long imageId){
        try {
            // get pixel id
            System.out.println("image ID in rendering settings : "+imageId );
            long pixelsId = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId).getDefaultPixels().getId();
            System.out.println("pixel ID in rendering settiongs : "+pixelsId );
            // get rendering settings
            RenderingDef renderingDef = client.getGateway().getRenderingSettingsService(client.getContext()).getRenderingSettings(pixelsId);

            if(renderingDef == null) {
                // load rendering settings if they were not automatically loaded
                RenderingEnginePrx re = client.getGateway().getRenderingService(client.getContext(), pixelsId);
                re.lookupPixels(pixelsId);
                if (!(re.lookupRenderingDef(pixelsId))) {
                    re.resetDefaultSettings(true);
                    re.lookupRenderingDef(pixelsId);
                }
                re.load();
                re.close();
                return client.getGateway().getRenderingSettingsService(client.getContext()).getRenderingSettings(pixelsId);
            }
            return renderingDef;

        } catch(ExecutionException | DSOutOfServiceException | ServerError | NullPointerException e){
            Dialogs.showErrorNotification("Rendering def reading","Could not read rendering settings on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return null;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Rendering def reading","You don't have the right to access to the Rendering setting of the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * read all project represented by the list of ids
     *
     * @param client
     * @param projectIds
     * @return
     */
    public static Collection<ProjectData> readOmeroProjects(OmeroRawClient client, List<Long> projectIds){
        try {
            return client.getGateway().getFacility(BrowseFacility.class).getProjects(client.getContext(), projectIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects","An error occurs when reading OMERO projects "+projectIds);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects","You don't have the right to access OMERO projects "+projectIds);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * read all projects for a certain user.
     *
     * @param client
     * @param userId
     * @return
     */
    public static Collection<ProjectData> readOmeroProjectsByUser(OmeroRawClient client, long userId){
        try {
            return client.getGateway().getFacility(BrowseFacility.class).getProjects(client.getContext(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * read all datasets given by the list of ids
     *
     * @param client
     * @param datasetIds
     * @return
     */
    public static Collection<DatasetData> readOmeroDatasets(OmeroRawClient client, List<Long> datasetIds){
        try {
            return client.getGateway().getFacility(BrowseFacility.class).getDatasets(client.getContext(), datasetIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading OMERO datasets "+datasetIds);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading datasets","You don't have the right to access OMERO datasets "+datasetIds);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    public static ImageData readOmeroImage(OmeroRawClient client, long imageId){
        try {
            return client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading image","An error occurs when reading OMERO image "+imageId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch (DSAccessException | NoSuchElementException e){
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * read image channels
     *
     * @param client
     * @param imageId
     * @return
     */
    public static List<ChannelData> readOmeroChannels(OmeroRawClient client, long imageId){
        try {
            // get channels
            return client.getGateway().getFacility(MetadataFacility.class).getChannelData(client.getContext(), imageId);
        } catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Channel reading","Could not read image channel on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Channel reading","You don't have the right to read channels on OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * read all annotations attached to an image on OMERO
     *
     * @param client
     * @param imageId
     * @return
     */
    public static List<AnnotationData> readOmeroAnnotations(OmeroRawClient client, long imageId){
        try {
            // get current image from OMERO
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // read annotations linked to the image
            return client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), imageData);

        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the read annotations on OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    public static String readImageFileType(OmeroRawClient client, long imageId){
        try {
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);
            return imageData.asImage().getFormat().getValue().getValue();
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return "";
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the read annotations on OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return "";
        }
    }

    /**
     * Convert a QuPath measurement table to an OMERO table
     *
     * @param pathObjects
     * @param ob
     * @return
     */
    public static TableData convertMeasurementTableToOmeroTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, OmeroRawClient client, long imageId) {
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

        // add the first column with the image data (linkable on OMERO)
        columns.add(new TableDataColumn("Image ID",i++, ImageData.class));
        ImageData image = readOmeroImage(client, imageId);
        List<Object> imageData = new ArrayList<>();

        for (PathObject ignored : pathObjects) {
            imageData.add(image);
        }
        measurements.add(imageData);

        // create formatted Lists of measurements to be compatible with omero.tables
        for (String col : ob.getAllNames()) {
            if (ob.isNumericMeasurement(col)) {
                // feature name
                columns.add(new TableDataColumn(col.replace("/","-"), i++, Double.class)); // OMERO table does not support "/"

                //feature value for each pathObject
                List<Object> feature = new ArrayList<>();
                for (PathObject pathObject : pathObjects) {
                    feature.add(ob.getNumericValue(pathObject, col));
                }
                measurements.add(feature);
            }

            if (ob.isStringMeasurement(col)) {
                // feature name
                columns.add(new TableDataColumn(col.replace("/","-"), i++, String.class)); // OMERO table does not support "/"

                //feature value for each pathObject
                List<Object> feature = new ArrayList<>();
                for (PathObject pathObject : pathObjects) {
                    feature.add(ob.getStringValue(pathObject, col));
                }
                measurements.add(feature);
            }
        }

        // create omero Table
        return new TableData(columns, measurements);
    }

    /**
     * Send an OMERO.table to OMERO
     *
     * @param table
     * @param name
     * @param client
     * @param imageId
     * @return
     */
    public static boolean addTableToOmero(TableData table, String name, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // attach the omero.table to the image
            client.getGateway().getFacility(TablesFacility.class).addTable(client.getContext(), image, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Send an attachment to OMERO
     *
     * @param file
     * @param client
     * @param imageId
     * @return
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId) {
        return addAttachmentToOmero(file, client, imageId, null,"");
    }

    /**
     *  Send an attachment to OMERO, specifying the mimetype
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @return
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype) {
        return addAttachmentToOmero(file, client, imageId, miemtype,"");
    }

    /**
     * Send an attachment to OMERO, specifying the mimetype and description
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @param description
     * @return
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype, String description) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // attach the omero.table to the image
            client.getGateway().getFacility(DataManagerFacility.class).attachFile(client.getContext(), file, miemtype, description, file.getName(), image).get();

        } catch (ExecutionException | DSOutOfServiceException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("File Saving","You don't have the right to save a file on OMERO for the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * update a list of object on OMERO
     *
     * @param client
     * @param objects
     * @return
     */
    public static boolean updateObjectsOnOmero(OmeroRawClient client, List<IObject> objects){
       boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getContext(), objects, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update objects","Error during updating objects on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update objects","You don't have the right to update objects on OMERO ");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * update an existing object on OMERO.
     *
     * @param client
     * @param object
     * @return
     */
    public static boolean updateObjectOnOmero(OmeroRawClient client, IObject object){
        boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getGateway().getFacility(DataManagerFacility.class).updateObject(client.getContext(), object, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update object","Error during updating object on OMERO.");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update object","You don't have the right to update object on OMERO ");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * update thumbnail image on OMERO giving the ID of the updated RenderingDef object.
     * Be careful : the object should already have an OMERO Id.
     *
     * @param client
     * @param imageId
     * @param objectId
     * @return
     */
    public static boolean updateOmeroThumbnail(OmeroRawClient client, long imageId, long objectId){
        boolean wasAdded = true;

        // get the current image
        ImageData image = readOmeroImage(client, imageId);
        System.out.println("image id when update thumbnail : " +imageId);
        System.out.println("rendering settings id when update thumbnail : "+objectId);

        ThumbnailStorePrx store = null;
        try {
            store = client.getGateway().getThumbnailService(client.getContext());
        } catch(DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot get the Thumbnail service for image " + imageId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
           return false;
        }

        if(store == null){
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot get the Thumbnail service for image " + imageId);
            return false;
        }

        try {
            // get the pixel id to retrieve the correct thumbnail
            long pixelId = image.getDefaultPixels().getId();
            System.out.println("pixel id   " + pixelId);
            // get current thumbnail
            store.setPixelsId(pixelId);
            //set the new settings
            store.setRenderingDefId(objectId);
            // update the thumbnail
            Thread.sleep(1000);
            store.createThumbnails();
        } catch (ServerError | NullPointerException | InterruptedException e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Thumbnail cannot be updated for image " + imageId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }

        try {
            store.close();
        } catch (ServerError e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot close the ThumbnailStore");
        }

        return wasAdded;
    }


    /**
     * write the measurement table as a csv file
     *
     * @param pathObjects
     * @param ob
     * @param path
     */
    public static File buildCSVFileFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, long imageId, String name, String path) {
        StringBuilder tableString = new StringBuilder();

        // get the header
        tableString.append("Image_ID").append(",");
        for (String col : ob.getAllNames()) {
            tableString.append(col).append(",");
        }
        tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
        tableString.append("\n");

        // get the table
        for (PathObject pathObject : pathObjects) {
            // add image id
            tableString.append(imageId).append(",");
            for (String col : ob.getAllNames()) {
                if (ob.isNumericMeasurement(col))
                    tableString.append(ob.getNumericValue(pathObject, col)).append(",");
                else
                    tableString.append(ob.getStringValue(pathObject, col)).append(",");
            }
            tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
            tableString.append("\n");
        }

        // create the file locally
        File file = new File(path + File.separator + name + ".csv");

       try {
           // write the file
           BufferedWriter buffer = new BufferedWriter(new FileWriter(file));
           buffer.write(tableString + "\n");

           // close the file
           buffer.close();

       } catch (IOException e) {
           Dialogs.showErrorNotification("Write CSV file", "An error has occurred when trying to save the csv file");
           logger.error("" + e);
           logger.error(getErrorStackTraceAsString(e));
       }

       return file;
    }

    /**
     * Delete all existing ROIs on OMERO that are linked to the current image
     *
     * @param client
     * @param imageId
     */
    public static void deleteOmeroROIs(OmeroRawClient client, long imageId) {
        try {
            // get existing OMERO ROIs
            List<ROIResult> roiList = client.getGateway().getFacility(ROIFacility.class).loadROIs(client.getContext(), imageId);

            // extract ROIData
            List<IObject> roiData = new ArrayList<>();
            roiList.forEach(roiResult -> roiData.addAll(roiResult.getROIs().stream().map(ROIData::asIObject).collect(Collectors.toList())));

            // delete ROis
            if(client.getGateway().getFacility(DataManagerFacility.class).delete(client.getContext(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete of cannot delete them");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete ROIs on OMERO on the image  " + imageId);
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
        }
    }


    /**
     * Save ROIs to OMERO, to the current image.
     *
     * @param client
     * @param imageId
     * @param omeroRois
     * @return
     */
    public static boolean writeOmeroROIs(OmeroRawClient client, long imageId, List<ROIData> omeroRois) {
        boolean roiSaved = false;

        // import ROIs on OMERO
        if (!(omeroRois.isEmpty())) {
            try {
                // save ROIs
                client.getGateway().getFacility(ROIFacility.class).saveROIs(client.getContext(), imageId, client.getGateway().getLoggedInUser().getId(), omeroRois);
                roiSaved = true;
            } catch (ExecutionException | DSOutOfServiceException e){
                Dialogs.showErrorNotification("ROI Saving","Error during saving ROIs on OMERO.");
                logger.error(""+e);
                logger.error(getErrorStackTraceAsString(e));
            } catch (DSAccessException e){
                Dialogs.showErrorNotification("ROI Saving","You don't have the right to write ROIs from OMERO on the image "+imageId);
                logger.error(""+e);
                logger.error(getErrorStackTraceAsString(e));
            }
        } else {
            Dialogs.showInfoNotification("Upload annotations","There is no Annotations to upload on OMERO");
        }

        return roiSaved;
    }

    /**
     * Read ROIs from OMERO, from the current image
     *
     * @param client
     * @param imageId
     * @return
     */
    public static List<ROIData> readOmeroROIs(OmeroRawClient client, long imageId){
        List<ROIResult> roiList;

        // get ROIs from OMERO
        try {
            roiList = client.getGateway().getFacility(ROIFacility.class).loadROIs(client.getContext(), imageId);
        } catch (DSOutOfServiceException | ExecutionException e) {
            Dialogs.showErrorNotification("ROI reading","Error during reading ROIs from OMERO.");
            logger.error("" + e);
            logger.error(getErrorStackTraceAsString(e));
            return new ArrayList<>();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("ROI reading","You don't have the right to read ROIs from OMERO on the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return new ArrayList<>();
         }

        if(roiList == null || roiList.isEmpty())
            return new ArrayList<>();

        // Convert them into ROIData
        List<ROIData> roiData = new ArrayList<>();
        for (ROIResult roiResult : roiList) {
            roiData.addAll(roiResult.getROIs());
        }

        return roiData;
    }

    /**
     * Convert a collection of pathObjects to a list of OMERO ROIs
     * @param pathObjects
     * @return
     */
    public static List<ROIData> createOmeroROIsFromPathObjects(Collection<PathObject> pathObjects){
        List<ROIData> omeroRois = new ArrayList<>();
        Map<PathObject,String> idObjectMap = new HashMap<>();

        // create unique ID for each object
        pathObjects.forEach(pathObject -> idObjectMap.put(pathObject, pathObject.getName()));

        pathObjects.forEach(pathObject -> {
            // computes OMERO-readable ROIs
            List<ShapeData> shapes = OmeroRawShapes.convertQuPathRoiToOmeroRoi(pathObject, idObjectMap.get(pathObject), pathObject.getParent() == null ? "NoParent" : idObjectMap.get(pathObject.getParent()));
            if (!(shapes == null) && !(shapes.isEmpty())) {
                // set the ROI color according to the class assigned to the corresponding PathObject
                shapes.forEach(shape -> {
                    shape.getShapeSettings().setStroke(pathObject.getPathClass() == null ? Color.YELLOW : new Color(pathObject.getPathClass().getColor()));
                    shape.getShapeSettings().setFill(!QPEx.getQuPath().getOverlayOptions().getFillAnnotations() ? null : pathObject.getPathClass() == null ? ColorToolsAwt.getMoreTranslucentColor(Color.YELLOW) : ColorToolsAwt.getMoreTranslucentColor(new Color(pathObject.getPathClass().getColor())));
                });
                ROIData roiData = new ROIData();
                shapes.forEach(roiData::addShapeData);
                omeroRois.add(roiData);
            }
        });

        return omeroRois;
    }

    /**
     * Convert a list of OMERO ROIs to a collection of pathObjects
     * @param roiData
     * @return
     */
    public static Collection<PathObject> createPathObjectsFromOmeroROIs(List<ROIData> roiData){
        Map<Double,Double> idParentIdMap = new HashMap<>();
        Map<Double,PathObject> idObjectMap = new HashMap<>();

        for (ROIData roiDatum : roiData) {
            // get the comment attached to OMERO ROIs
            List<String> roiCommentsList = getROIComment(roiDatum);

            // check that all comments are identical for all the shapes attached to the same ROI.
            // if there are different, a warning is thrown because they should be identical.
            String roiComment = "";
            if(!roiCommentsList.isEmpty()) {
                roiComment = roiCommentsList.get(0);
                for (int i = 0; i < roiCommentsList.size() - 1; i++) {
                    if (!(roiCommentsList.get(i).equals(roiCommentsList.get(i + 1)))) {
                        logger.warn("Different classes are set for two shapes link to the same parent");
                        logger.warn("The following class will be assigned for all child object -> "+roiComment);
                    }
                }
            }

            // get the type, class, id and parent id of thu current ROI
            String[] roiCommentParsed = parseROIComment(roiComment);
            String roiType = roiCommentParsed[0];
            String roiClass = roiCommentParsed[1];
            double roiId = Double.parseDouble(roiCommentParsed[2]);
            double parentId = Double.parseDouble(roiCommentParsed[3]);

            // convert OMERO ROIs to QuPath ROIs
            ROI qpROI = OmeroRawShapes.convertOmeroROIsToQuPathROIs(roiDatum);

            // convert QuPath ROI to QuPath Annotation or detection Object (according to type).
            idObjectMap.put(roiId, OmeroRawShapes.createPathObjectFromQuPathRoi(qpROI, roiType, roiClass));

            // populate parent map with current_object/parent ids
            idParentIdMap.put(roiId,parentId);
        }

        // set the parent/child hierarchy and add objects without any parent to the final list
        List<PathObject> pathObjects = new ArrayList<>();

        idParentIdMap.keySet().forEach(objID->{
            // if the current object has a valid id and has a parent
            if(objID > 0 && idParentIdMap.get(objID) > 0 && !(idObjectMap.get(idParentIdMap.get(objID)) == null))
                idObjectMap.get(idParentIdMap.get(objID)).addChildObject(idObjectMap.get(objID));
            else
                // if no valid id for object or if the object has no parent
                pathObjects.add(idObjectMap.get(objID));
        });

        return pathObjects;
    }

    /**
     * Get the comment attached to one shape of the OMERO ROI.
     *
     * @param shape
     * @return
     */
    public static String getROIComment(Shape shape){
        if(shape instanceof Rectangle){
            RectangleData s = new RectangleData(shape);
            return s.getText();
        }else if(shape instanceof Ellipse){
            EllipseData s = new EllipseData(shape);
            return s.getText();
        }else if(shape instanceof Point){
            PointData s = new PointData(shape);
            return s.getText();
        }else if(shape instanceof Polyline){
            PolylineData s = new PolylineData(shape);
            return s.getText();
        }else if(shape instanceof Polygon){
            PolygonData s = new PolygonData(shape);
            return s.getText();
        }else if(shape instanceof Label){
            logger.warn("No ROIs created (requested label shape is unsupported)");
            //s=new TextData(shape);
        }else if(shape instanceof Line){
            LineData s = new LineData(shape);
            return s.getText();
        }else if(shape instanceof Mask){
            logger.warn("No ROIs created (requested Mask shape is not supported yet)");
            //s=new MaskData(shape);
        }else{
            logger.warn("Unsupported shape ");
        }

        return null;
    }

    /**
     * Read the comments attach to the current ROI in OMERO (i.e. read each comment attached to each shape)
     *
     * @param roiData
     * @return
     */
    public static List<String> getROIComment(ROIData roiData) {
        // get the ROI
        Roi omeROI = (Roi) roiData.asIObject();

        // get the shapes contained in the ROI (i.e. holes or something else)
        List<Shape> shapes = omeROI.copyShapes();
        List<String> list = new ArrayList<>();

        // Iterate on shapes, select the correct instance and get the comment attached to it.
        for (Shape shape : shapes) {
            String comment = getROIComment(shape);
            if (comment != null)
                list.add(comment);
        }

        return list;
    }

    /**
     * Parse the comment based on the standardization introduced in "OmeroRawShapes.setRoiComment()"
     *
     * @param comment
     * @return
     */
    public static String[] parseROIComment(String comment) {
        // default parsing
        String roiClass = "NoClass";
        String roiType = "annotation";
        String roiParent =  "0";
        String roiID =  "-"+System.nanoTime();

        // split the string
        String[] tokens = (comment.isBlank() || comment.isEmpty()) ? null : comment.split(":");
        if(tokens == null)
            return new String[]{roiType, roiClass, roiID, roiParent};

        // get ROI type
        if (tokens.length > 0)
            roiType = tokens[0];

        // get the class
        if(tokens.length > 1)
            roiClass = tokens[1];

        // get the ROI id
        if(tokens.length > 2) {
            try {
                Double.parseDouble(tokens[2]);
                roiID = tokens[2];
            } catch (NumberFormatException e) {
                roiID = "-"+System.nanoTime();
            }
        }

        // get the parent ROI id
        if(tokens.length > 3) {
            try {
                Double.parseDouble(tokens[3]);
                roiParent = tokens[3];
            } catch (NumberFormatException e) {
                roiParent = "0";
            }
        }

        return new String[]{roiType, roiClass, roiID, roiParent};
    }


    /**
     * Splits the "target" map into two : one containing key/values that are referenced in the "reference" map and
     * the other containing remaining key/values that are not referenced in the "reference".
     *
     * @param reference
     * @param target
     * @return
     */
    public static List<Map<String, String>> splitNewAndExistingKeyValues(Map<String, String> reference, Map<String, String> target){
        Map<String, String> existingKVP = new HashMap<>();

        // filter key/values that are contained in the reference
        reference.forEach((key, value) -> existingKVP.putAll(target.keySet()
                .stream()
                .filter(f -> f.equals(key))
                .collect(Collectors.toMap(e->key,e->target.get(key)))));

        // filter the new key values
        Map<String,String> updatedKV = target.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        existingKVP.forEach(updatedKV::remove);

        // add the two separate maps to a list.
        List<Map<String, String>> results = new ArrayList<>();
        results.add(existingKVP);
        results.add(updatedKV);

        return results;
    }


    /**
     * Read key value pairs from OMERO server.
     *
     * Code partially taken from Pierre Pouchin, from his "simple-omero-client" project
     *
     * @param client
     * @param imageId
     */
    public static List<MapAnnotationData> readKeyValues(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // read annotations linked to the image
            annotations = client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), imageData);

        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO key value pairs", "Cannot get key values from OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter key values
        return annotations.stream()
                .filter(MapAnnotationData.class::isInstance)
                .map(MapAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Read key value pairs from OMERO and convert them into NamedValues. This OMERO-compatible object is more flexible
     * than a Map&lt;String, String&gt; and therefore allows for two identical keys (Name).
     *
     * @param client
     * @param imageId
     * @return
     */
    public static List<NamedValue> readKeyValuesAsNamedValue(OmeroRawClient client, long imageId) {
        return readKeyValues(client, imageId).stream()
                .flatMap(e->((List<NamedValue>)(e.getContent())).stream())
                .collect(Collectors.toList());
    }


    /**
     * Add new key value pairs to an image on OMERO.
     *
     * @param keyValuePairs
     * @param client
     * @param imageId
     */
    public static boolean addKeyValuesOnOmero(MapAnnotationData keyValuePairs, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // send key-values to OMERO
            client.getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getContext(), keyValuePairs, imageData);

        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "Cannot add new key values on OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to add some key value pairs on OMERO on the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Update specified key value pairs on OMERO
     *
     * @param keyValuePairs
     * @param client
     */
    public static boolean updateKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasUpdated = true;
        try {
            // update key-values to OMERO
            client.getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getContext(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()),null);
        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("OMERO KeyValues update", "Cannot update existing key values on OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to update key value pairs on OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }
        return wasUpdated;
    }

    /**
     * Delete specified key value pairs on OMERO
     *
     * @param keyValuePairs
     * @param client
     */
    public static boolean deleteKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasDeleted = true;
        try {
            // remove current key-values
            client.getGateway().getFacility(DataManagerFacility.class).delete(client.getContext(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()));
        } catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("OMERO KeyValues deletion", "Cannot delete existing key values on OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasDeleted = false;
        }
        return wasDeleted;
    }



    /**
     * Try to solve an error in OMERO regarding the creation keys.
     * On OMERO, it is possible to have two identical keys with a different value. This should normally never append.
     * This method check if all keys are unique and output false if there is at least two identical keys.
     *
     *
     * @param keyValues
     * @return
     */
    public static boolean checkUniqueKeyInAnnotationMap(List<NamedValue> keyValues){ // not possible to have a map because it allows only unique keys
        boolean uniqueKey = true;

        for(int i = 0; i < keyValues.size()-1;i++){
            for(int j = i+1;j < keyValues.size();j++){
                if(keyValues.get(i).name.equals(keyValues.get(j).name)){
                    uniqueKey = false;
                    break;
                }
            }
            if(!uniqueKey)
                break;
        }
        return uniqueKey;
    }


    /**
     * Read tags from OMERO server.
     *
     * @param client
     * @param imageId
     * @return
     */
    public static List<TagAnnotationData> readTags(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // read annotations linked to the image
            annotations = client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO Tags", "Cannot get tags from OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "You don't have the right to read tags from OMERO on the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter tags
        return annotations.stream()
                .filter(TagAnnotationData.class::isInstance)
                .map(TagAnnotationData.class::cast)
                .collect(Collectors.toList());
    }


    /**
     *  Add a new tag to an image on OMERO.
     *
     * @param tags
     * @param client
     * @param imageId
     * @return
     */
    public static boolean addTagsOnOmero(TagAnnotationData tags, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);

            // send key-values to OMERO
            client.getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getContext(), tags, imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "Cannot add new tags on OMERO");
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "You don't have the right to add tags on OMERO on the image "+imageId);
            logger.error(""+e);
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Return the thumbnail of the OMERO image corresponding to the specified {@code imageId}.
     *
     * Code copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getThumbnail} method
     * and adapted for QuPath compatibility.
     *
     * @param client
     * @param imageId
     * @param prefSize
     * @return thumbnail
     */
    public static BufferedImage getThumbnail(OmeroRawClient client, long imageId, int prefSize) {

        // get the current defaultPixel
        PixelsData pixel;
        try {
            pixel = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId).getDefaultPixels();
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException | NullPointerException e){
            Dialogs.showErrorNotification( "Error retrieving image and pixels for thumbnail :","" +e);
            return readLocalImage(noImageThumbnail);
        }

        // set the thumbnail size
        int   sizeX  = pixel.getSizeX();
        int   sizeY  = pixel.getSizeY();
        float ratioX = (float) sizeX / prefSize;
        float ratioY = (float) sizeY / prefSize;
        float ratio  = Math.max(ratioX, ratioY);
        int   width  = (int) (sizeX / ratio);
        int   height = (int) (sizeY / ratio);

        // get rendering settings for the current image
        RenderingDef renderingSettings = readOmeroRenderingSettings(client, imageId);

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageId);
            return readLocalImage(noImageThumbnail);
        }

        // get thumbnail
        byte[] array;
        try {
            ThumbnailStorePrx store = client.getGateway().getThumbnailService(client.getContext());
            store.setPixelsId(pixel.getId());
            store.setRenderingDefId(renderingSettings.getId().getValue());
            array = store.getThumbnail(rint(width), rint(height));
            store.close();
        } catch (DSOutOfServiceException | ServerError | NullPointerException e) {
            Dialogs.showErrorNotification( "Error retrieving thumbnail :","" +e);
            return readLocalImage(noImageThumbnail);
        }

        // convert thumbnail into BufferedImage
        if (array != null) {
            try (ByteArrayInputStream stream = new ByteArrayInputStream(array)) {
                //Create a buffered image to display
                BufferedImage thumbnail = ImageIO.read(stream);
                if(thumbnail == null)
                    return readLocalImage(noImageThumbnail);
                else return thumbnail;
            }catch(IOException e){
                Dialogs.showErrorNotification( "Error converting thumbnail to bufferedImage :","" +e);
                return readLocalImage(noImageThumbnail);
            }
        }
        else return readLocalImage(noImageThumbnail);
    }


    /**
     * read an image stored in the resource folder of the main class
     *
     * @param imageName
     * @return
     */
    public static BufferedImage readLocalImage(String imageName){
        try {
            return ImageIO.read(OmeroRawTools.class.getClassLoader().getResource("images/"+imageName));
        }catch(IOException e){
            return null;
        }
    }


//	/**
//	 * Return a list of all {@code OmeroWebClient}s that are using the specified URI (based on their {@code host}).
//	 * @param uri
//	 * @return
//	 */
//	public static OmeroWebClient getWebClients(URI uri) {
//		var webclients = OmeroWebClients.getAllClients();
//		return webclients.values().parallelStream().flatMap(List::stream).filter(e -> e.getURI().getHost().equals(uri.getHost())).collect(Collectors.toList());
//	}


    /**
     * Return a clean URI of the server from which the given URI is specified. This method relies on
     * the specified {@code uri} to be formed properly (with at least a scheme and a host).
     * <p>
     * A few notes:
     * <ul>
     * <li> If the URI does not contain a host (but does a path), it will be returned without modification. </li>
     * <li> If no host <b>and</b> no path is found, {@code null} is returned. </li>
     * <li> If the specified {@code uri} does not contain a scheme, {@code https://} will be used. </li>
     * </ul>
     * <p>
     * E.g. {@code https://www.my-server.com/show=image-462} returns {@code https://www.my-server.com/}
     *
     * @param uri
     * @return clean uri
     */
    public static URI getServerURI(URI uri) {
        if (uri == null)
            return null;

        try {
            var host = uri.getHost();
            var path = uri.getPath();
            if (host == null || host.isEmpty())
                return (path == null || path.isEmpty()) ? null : uri;

            var scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty())
                scheme = "https://";
            return new URL(scheme, host, uri.getPort(), "").toURI();
        } catch (MalformedURLException | URISyntaxException ex) {
            logger.error("Could not parse server from {}: {}", uri, ex.getLocalizedMessage());
        }
        return null;
    }


    /**
     * OMERO requests that return a list of items are paginated
     * (see <a href="https://docs.openmicroscopy.org/omero/5.6.1/developers/json-api.html#pagination">OMERO API docs</a>).
     * Using this helper method ensures that all the requested data is retrieved.
     *
     * @param url
     * @return list of {@code Json Element}s
     * @throws IOException
     */
    // TODO: Consider using parallel/asynchronous requests
    static List<JsonElement> readPaginated(URL url) throws IOException {
        List<JsonElement> jsonList = new ArrayList<>();
        String symbol = (url.getQuery() != null && !url.getQuery().isEmpty()) ? "&" : "?";

        // Open connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int response = connection.getResponseCode();

        // Catch bad response
        if (response != 200)
            return jsonList;

        JsonObject map;
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
            map = GsonTools.getInstance().fromJson(reader, JsonObject.class);
        }

        map.get("data").getAsJsonArray().forEach(jsonList::add);
        JsonObject meta = map.getAsJsonObject("meta");
        int offset = 0;
        int totalCount = meta.get("totalCount").getAsInt();
        int limit = meta.get("limit").getAsInt();
        while (offset + limit < totalCount) {
            offset += limit;
            URL nextURL = new URL(url + symbol + "offset=" + offset);
            InputStreamReader newPageReader = new InputStreamReader(nextURL.openStream());
            JsonObject newPageMap = GsonTools.getInstance().fromJson(newPageReader, JsonObject.class);
            newPageMap.get("data").getAsJsonArray().forEach(jsonList::add);
        }
        return jsonList;
    }


    /**
     * Return a list of valid URIs from the given URI. If no valid URI can be parsed
     * from it, an IOException is thrown.
     * <p>
     * E.g. "{@code /host/webclient/?show=image=4|image=5}" returns a list containing:
     * "{@code /host/webclient/?show=image=4}" and "{@code /host/webclient/?show=image=5}".
     *
     * @param uri
     * @return list
     * @throws IOException
     */

    static List<URI> getURIs(URI uri, OmeroRawClient client) throws IOException, DSOutOfServiceException, ExecutionException, DSAccessException {
        List<URI> list = new ArrayList<>();
        URI cleanServerUri = URI.create(uri.toString().replace("show%3Dimage-", "show=image-"));
        String elemId = "image-";
        String query = cleanServerUri.getQuery() != null ? uri.getQuery() : "";
        String shortPath = cleanServerUri.getPath() + query;

        Pattern[] similarPatterns = new Pattern[] {patternOldViewer, patternNewViewer, patternWebViewer};

        // Check for simpler patterns first
        for (int i = 0; i < similarPatterns.length; i++) {
            var matcher = similarPatterns[i].matcher(shortPath);
            if (matcher.find()) {
                elemId += matcher.group(1);

                try {
                    list.add(new URL(cleanServerUri.getScheme(), cleanServerUri.getHost(), cleanServerUri.getPort(), "/webclient/?show=" + elemId).toURI());
                } catch (MalformedURLException | URISyntaxException ex) {
                    logger.warn(ex.getLocalizedMessage());
                }
                return list;
            }
        }

        // If no simple pattern was matched, check for the last possible one: /webclient/?show=
        if (shortPath.startsWith("/webclient/show")) {
            URI newURI = getStandardURI(uri, client);
            var patternElem = Pattern.compile("image-(\\d+)");
            var matcherElem = patternElem.matcher(newURI.toString());
            while (matcherElem.find()) {
                list.add(URI.create(String.format("%s://%s%s%s%s%s",
                        uri.getScheme(),
                        uri.getHost(),
                        uri.getPort() >= 0 ? ":" + uri.getPort() : "",
                        uri.getPath(),
                        "?show=image-",
                        matcherElem.group(1))));
            }
            return list;
        }

        // At this point, no valid URI pattern was found
        throw new IOException("URI not recognized: " + uri);
    }

    static URI getStandardURI(URI uri, OmeroRawClient client) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
        List<String> ids = new ArrayList<>();
        String vertBarSign = "%7C";

        // Identify the type of element shown (e.g. dataset)
        OmeroRawObjects.OmeroRawObjectType type;
        String query = uri.getQuery() != null ? uri.getQuery() : "";

        // Because of encoding, the equal sign might not be recognized when loading .qpproj file
        query = query.replace("%3D", "=");

        // Match
        var matcherType = patternType.matcher(query);
        if (matcherType.find())
            type = OmeroRawObjects.OmeroRawObjectType.fromString(matcherType.group(1).replace("-", ""));
        else
            throw new IOException("URI not recognized: " + uri);

        var patternId = Pattern.compile(type.toString().toLowerCase() + "-(\\d+)");
        var matcherId = patternId.matcher(query);
        while (matcherId.find()) {
            ids.add(matcherId.group(1));
        }

        // Cascading the types to get all ('leaf') images
        StringBuilder sb = new StringBuilder(
                String.format("%s://%s%s%s%s",
                        uri.getScheme(),
                        uri.getHost(),
                        uri.getPort() >= 0 ? ":" + uri.getPort() : "",
                        uri.getPath(),
                        "?show=image-"));

        List<String> tempIds = new ArrayList<>();
        // TODO: Support screen and plates
        switch (type) {
            case SCREEN:
            case PLATE:
            case WELL:
                break;
            case PROJECT:
                for (String id: ids) {
                    tempIds.add(client.getGateway().getFacility(BrowseFacility.class).getProjects(client.getContext(),Collections.singletonList(Long.parseLong(id))).iterator().next().getDatasets()
                                    .stream()
                                    .map(DatasetData::asDataset)
                                    .map(Dataset::getId)
                                    .map(RLong::getValue)
                                    .toString());
                }
                ids =  new ArrayList<>(tempIds);
                tempIds.clear();
                type = OmeroRawObjects.OmeroRawObjectType.DATASET;

            case DATASET:
                for (String id: ids) {
                    tempIds.add(client.getGateway().getFacility(BrowseFacility.class).getImagesForDatasets(client.getContext(),Collections.singletonList(Long.parseLong(id)))
                            .stream()
                            .map(ImageData::asImage)
                            .map(Image::getId)
                            .map(RLong::getValue)
                            .toString());
                }
                ids = new ArrayList<>(tempIds);
                tempIds.clear();
                type = OmeroRawObjects.OmeroRawObjectType.IMAGE;

            case IMAGE:
                if (ids.isEmpty())
                    logger.info("No image found in URI: " + uri);
                for (int i = 0; i < ids.size(); i++) {
                    String imgId = (i == ids.size()-1) ? ids.get(i) : ids.get(i) + vertBarSign + "image-";
                    sb.append(imgId);
                }
                break;
            default:
                throw new IOException("No image found in URI: " + uri);
        }

        return URI.create(sb.toString());
    }

    static Node createStateNode(boolean loggedIn) {
        var state = loggedIn ? IconFactory.PathIcons.ACTIVE_SERVER : IconFactory.PathIcons.INACTIVE_SERVER;
        return IconFactory.createNode(QuPathGUI.TOOLBAR_ICON_SIZE, QuPathGUI.TOOLBAR_ICON_SIZE, state);
    }



    /**
     * Return the Id associated with the {@code URI} provided.
     * If multiple Ids are present, only the first one will be retrieved.
     * If no Id could be found, return -1.
     *
     * @param uri
     * @param type
     * @return Id
     */
    public static int parseOmeroRawObjectId(URI uri, OmeroRawObjects.OmeroRawObjectType type) {
        String cleanUri = uri.toString().replace("%3D", "=");
        Matcher m;
        switch (type) {
            case SERVER:
                logger.error("Cannot parse an ID from OMERO server.");
                break;
            case PROJECT:
                m = patternLinkProject.matcher(cleanUri);
                if (m.find()) return Integer.parseInt(m.group(1));
                break;
            case DATASET:
                m = patternLinkDataset.matcher(cleanUri);
                if (m.find()) return Integer.parseInt(m.group(1));
                break;
            case IMAGE:
                for (var p: imagePatterns) {
                    m = p.matcher(cleanUri);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
                break;
            default:
                throw new UnsupportedOperationException("Type (" + type + ") not supported");
        }
        return -1;
    }

    /**
     * Return the type associated with the {@code URI} provided.
     * If multiple types are present, only the first one will be retrieved.
     * If no type is found, return UNKNOWN.
     * <p>
     * Accepts the same formats as the {@code OmeroRawImageServer} constructor.
     * <br>
     * E.g., https://{server}/webclient/?show=dataset-{datasetId}
     *
     * @param uri
     * @return omeroRawObjectType
     */
    public static OmeroRawObjects.OmeroRawObjectType parseOmeroRawObjectType(URI uri) {
        var uriString = uri.toString().replace("%3D", "=");
        if (patternLinkProject.matcher(uriString).find())
            return OmeroRawObjects.OmeroRawObjectType.PROJECT;
        else if (patternLinkDataset.matcher(uriString).find())
            return OmeroRawObjects.OmeroRawObjectType.DATASET;
        else {
            for (var p: imagePatterns) {
                if (p.matcher(uriString).find())
                    return OmeroRawObjects.OmeroRawObjectType.IMAGE;
            }
        }
        return OmeroRawObjects.OmeroRawObjectType.UNKNOWN;
    }

    public static String getErrorStackTraceAsString(Exception e){
        return Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).reduce("",(a,b)->a + "     at "+b+"\n");
    }
}
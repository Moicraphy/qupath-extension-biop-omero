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
import java.awt.geom.Area;
import java.awt.geom.Point2D;
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

import fr.igred.omero.Client;
import omero.RLong;
import omero.ServerError;
import omero.api.ThumbnailStorePrx;
import omero.gateway.SecurityContext;
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
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

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
     * Return the raw client used for the specified OMERO server.
     *
     * @param server
     * @return client
     */
    public static OmeroRawClient getRawClient(OmeroRawImageServer server) {
        return server.getClient();
    }


    /**
     * Get all the OMERO objects (inside the parent Id) present in the OMERO server with the specified
     * URI.
     * <p>
     * No orphaned {@code OmeroRawObject} will be fetched.
     *
     * @param client
     * @param parent
     * @return list of OmeroRawObjects
     * @throws IOException
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOmeroObjects(OmeroRawObjects.OmeroRawObject parent, OmeroRawClient client, SecurityContext groupCtx, OmeroRawObjects.Group group) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();
        if (parent == null)
            return list;

        OmeroRawObjects.OmeroRawObjectType type = OmeroRawObjects.OmeroRawObjectType.PROJECT;
        if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT)
            type = OmeroRawObjects.OmeroRawObjectType.DATASET;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET)
            type = OmeroRawObjects.OmeroRawObjectType.IMAGE;

        final OmeroRawObjects.OmeroRawObjectType finaltype = type;

        try {
            if (type == OmeroRawObjects.OmeroRawObjectType.PROJECT) {
                Collection<ProjectData> projects = new ArrayList<>();
                List<GroupExperimenterMap> owners = client.getGateway().getAdminService(client.getContext()).lookupGroup(group.getName()).copyGroupExperimenterMap();

                owners.forEach(owner ->{
                    try {
                        projects.addAll(client.getGateway().getFacility(BrowseFacility.class).getProjects(groupCtx, owner.getChild().getId().getValue()));
                    } catch (DSOutOfServiceException | DSAccessException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                });

                projects.forEach(e-> {
                    try {
                        list.add(new OmeroRawObjects.Project("",e,e.getId(),finaltype,client, parent));
                    } catch (DSOutOfServiceException | ServerError ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
            else if (type == OmeroRawObjects.OmeroRawObjectType.DATASET) {
                // get the current project to have access to the child datasets
                Collection<ProjectData> projectColl = client.getGateway().getFacility(BrowseFacility.class).getProjects(groupCtx,Collections.singletonList(parent.getId()));
                if(projectColl.iterator().next().asProject().sizeOfDatasetLinks() > 0){
                    List<DatasetData> datasets = new ArrayList<>();
                    List<ProjectDatasetLink> links = projectColl.iterator().next().asProject().copyDatasetLinks();

                    // get child datasets
                    for (ProjectDatasetLink link : links) {
                        Collection<DatasetData> datasetColl = client.getGateway().getFacility(BrowseFacility.class).getDatasets(groupCtx,Collections.singletonList(link.getChild().getId().getValue()));
                        datasets.add(datasetColl.iterator().next());
                    }

                    // create OmeroRawObjects from child datasets
                    datasets.forEach(e-> {
                        try {
                            list.add(new OmeroRawObjects.Dataset("",e,e.getId(),finaltype,client,parent));
                        } catch (DSOutOfServiceException | ServerError ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                }
            }
            else if (type == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                // get the current dataset to have access to the child images
                Collection<DatasetData> datasetColl = client.getGateway().getFacility(BrowseFacility.class).getDatasets(groupCtx,Collections.singletonList(parent.getId()));
                if(datasetColl.iterator().next().asDataset().sizeOfImageLinks() > 0){
                    List<ImageData> images = new ArrayList<>();
                    List<DatasetImageLink> links = datasetColl.iterator().next().asDataset().copyImageLinks();

                    // get child images
                    for (DatasetImageLink link : links) {
                        images.add(new ImageData(link.getChild()));
                    }

                    // create OmeroRawObjects from child images
                    images.forEach(e-> {
                        try {
                            list.add(new OmeroRawObjects.Image("",e,e.getId(),finaltype,client,parent));
                        } catch (DSOutOfServiceException | ServerError ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                }
            }
        } catch (DSOutOfServiceException | DSAccessException e) {
            throw new IOException("Cannot get datasets");
        } catch (ServerError e) {
            throw new RuntimeException(e);
        }

        return list;
    }

//	/**
//	 * Get all the orphaned images in the given server.
//	 *
//	 * @param uri
//	 * @return list of orphaned images
//	 * @see #populateOrphanedImageList(URI, OrphanedFolder)
//	 */
//	public static List<OmeroObject> readOrphanedImages(URI uri) {
//		List<OmeroObject> list = new ArrayList<>();
//
//		// Requesting orphaned images can time-out the JSON API on OMERO side if too many,
//		// so we go through the webclient, whose response comes in a different format.
//		try {
//			var map = OmeroRequests.requestWebClientObjectList(uri.getScheme(), uri.getHost(), OmeroObjectType.IMAGE);
//
//        	// Send requests in separate threads, this is not a great design
//        	// TODO: Clean up this code, which now does:
//        	// 1. Send request for each image in the list of orphaned images in the executor
//        	// 2. Terminate the executor after 5 seconds
//        	// 3. Checks if there are still requests that weren't processed and gives log error if so
//        	// Solution: Give a time-out for the request in readPaginated() :::
//        	//
//        	// URLConnection con = url.openConnection();
//        	// con.setConnectTimeout(connectTimeout);
//        	// con.setReadTimeout(readTimeout);
//        	// InputStream in = con.getInputStream();
//        	//
//    		ExecutorService executorRequests = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("orphaned-image-requests", true));
//    		List<Future<?>> futures = new ArrayList<Future<?>>();
//
//    		map.get("images").getAsJsonArray().forEach(e -> {
//    			// To keep track of the completed requests, keep a Future variable
//    			Future<?> future = executorRequests.submit(() -> {
//    				try {
//    					var id = Integer.parseInt(e.getAsJsonObject().get("id").toString());
//        				var omeroObj = readOmeroObject(uri.getScheme(), uri.getHost(), id, OmeroObjectType.IMAGE);
//        				list.add(omeroObj);
//    				} catch (IOException ex) {
//						logger.error("Could not fetch information for image id: " + e.getAsJsonObject().get("id"));
//					}
//    			});
//    			futures.add(future);
//        	});
//    		executorRequests.shutdown();
//    		try {
//    			// Wait 10 seconds to terminate tasks
//    			executorRequests.awaitTermination(10L, TimeUnit.SECONDS);
//    			long nPending = futures.parallelStream().filter(e -> !e.isDone()).count();
//    			if (nPending > 0)
//    				logger.warn("Too many orphaned images in " + uri.getHost() + ". Ignored " + nPending + " orphaned image(s).");
//    		} catch (InterruptedException ex) {
//    			logger.debug("InterrupedException occurred while interrupting requests.");
//    			Thread.currentThread().interrupt();
//    		}
//        } catch (IOException ex) {
//        	logger.error(ex.getLocalizedMessage());
//        }
//		return list;
//	}

    /**
     * return the Owner object corresponding to the logged in user
     * @param client
     * @return
     * @throws DSOutOfServiceException
     * @throws ServerError
     */
    public static OmeroRawObjects.Owner getDefaultOwner(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        Experimenter user = client.getGateway().getAdminService(client.getContext()).getExperimenter(client.getGateway().getLoggedInUser().getId());
        return new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                user.getLastName()==null ? "" : user.getLastName().getValue(),
                user.getEmail()==null ? "" : user.getEmail().getValue(),
                user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                user.getOmeName()==null ? "" : user.getOmeName().getValue());
    }


    /**
     * return the group object corresponding to the default group attributed to the logged in user
     * @param client
     * @return
     * @throws DSOutOfServiceException
     * @throws ServerError
     */
    public static OmeroRawObjects.Group getDefaultGroup(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        ExperimenterGroup userGroup = client.getGateway().getAdminService(client.getContext()).getDefaultGroup(client.getGateway().getLoggedInUser().getId());
        return new OmeroRawObjects.Group(userGroup.getId().getValue(), userGroup.getName().getValue());
    }


    /**
     * return a map of available groups with its attached users.
     *
     * @param client
     * @return
     * @throws DSOutOfServiceException
     * @throws ServerError
     */
    public static Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> getAvailableGroups(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> map = new HashMap<>();

        // get all available groups for the current user according to his admin rights
        List<ExperimenterGroup> groups;
        if(client.getGateway().getAdminService(client.getContext()).getCurrentAdminPrivileges().isEmpty())
            groups = client.getGateway().getAdminService(client.getContext()).containedGroups(client.getGateway().getLoggedInUser().getId());
        else
            groups = client.getGateway().getAdminService(client.getContext()).lookupGroups();

        groups.forEach(group-> {
            // get all available users for the current group
            List<Experimenter> users;
            try {
                users = client.getGateway().getAdminService(client.getContext()).containedExperimenters(group.getId().getValue());
            } catch (ServerError | DSOutOfServiceException e) {
                throw new RuntimeException(e);
            }

            List<OmeroRawObjects.Owner> owners = new ArrayList<>();
            OmeroRawObjects.Group userGroup = new OmeroRawObjects.Group(group.getId().getValue(), group.getName().getValue());

            for (Experimenter user : users) {

                owners.add(new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                        user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                        user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                        user.getLastName()==null ? "" : user.getLastName().getValue(),
                        user.getEmail()==null ? "" : user.getEmail().getValue(),
                        user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                        user.getOmeName()==null ? "" : user.getOmeName().getValue()));
            }

            owners.sort(Comparator.comparing(OmeroRawObjects.Owner::getName));
            map.put(userGroup, owners);

        });

        return new TreeMap<>(map);
    }

    /**
     * Get all the orphaned {@code OmeroRawObject}s of type Dataset from the server.
     *
     * @param client the client {@code OmeroRawClient} object
     * @param groupCtx security context of the current group
     * @return list of orphaned datasets
     * @throws IOException
     * @throws ServerError
     * @throws DSOutOfServiceException
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOrphanedDatasets(OmeroRawClient client, SecurityContext groupCtx) throws IOException, ServerError, DSOutOfServiceException {
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();
        Collection<DatasetData> orphanedDatasets = OmeroRawRequests.getOrphanedDatasets(client,groupCtx);

        orphanedDatasets.forEach( e -> {
            OmeroRawObjects.OmeroRawObject omeroObj;
            try {
                omeroObj = new OmeroRawObjects.Dataset("", e, e.getId(), OmeroRawObjects.OmeroRawObjectType.DATASET, client, new OmeroRawObjects.Server(client.getServerURI()));
                list.add(omeroObj);
            } catch (DSOutOfServiceException | ServerError ex) {
                throw new RuntimeException(ex);
            }

        });

        return list;
    }


    /**
     * Get all the orphaned {@code OmeroRawObject}s of type Image from the server.
     *
     * @param client the client {@code OmeroRawClient} object
     * @param groupCtx security context of the current group
     * @return list of orphaned images
     * @throws IOException
     * @throws ServerError
     * @throws DSOutOfServiceException
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOrphanedImages(OmeroRawClient client, SecurityContext groupCtx) throws IOException, ServerError, DSOutOfServiceException {
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();
        Collection<ImageData> orphanedImages = OmeroRawRequests.getOrphanedImages(client,groupCtx);

        orphanedImages.forEach( e -> {
            OmeroRawObjects.OmeroRawObject omeroObj;
            try {
                omeroObj = new OmeroRawObjects.Image("", e, e.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, client, new OmeroRawObjects.Server(client.getServerURI()));
                list.add(omeroObj);
            } catch (DSOutOfServiceException | ServerError ex) {
                throw new RuntimeException(ex);
            }

        });

        return list;
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


    /**
     * Request the {@code OmeroRawAnnotations} object of type {@code category} associated with
     * the {@code OmeroRawObject} specified.
     *
     * @param client
     * @param obj
     * @param category
     * @return omeroRawAnnotations object
     */
    public static OmeroRawAnnotations readOmeroAnnotations(OmeroRawClient client, OmeroRawObjects.OmeroRawObject obj, OmeroRawAnnotations.OmeroRawAnnotationType category) {
        try {
            List<?> annotations = client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), obj.getData());
            return OmeroRawAnnotations.getOmeroAnnotations(client, category, annotations);
        } catch (Exception ex) {
            logger.warn("Could not fetch {} information: {}", category, ex.getLocalizedMessage());
            return null;
        }
    }


    /**
     * This method creates an instance of {@code fr.igred.omero.Client} object to get access to the full
     * simple-omero-client API, developed by Pierre Pouchin (https://github.com/GReD-Clermont/simple-omero-client).
     *
     * @return the Client object
     */
    public static Client getSimpleOmeroClientInstance() throws DSOutOfServiceException {
        // get the current OmeroRawClient
        ImageServer<?> server = QP.getCurrentServer();
        OmeroRawClient omerorawclient = OmeroRawClients.getClientFromImageURI(server.getURIs().iterator().next());

        // build the simple-omero-client using the ID of the current session
        Client simpleClient = new Client();
        simpleClient.connect(omerorawclient.getServerURI().getHost(), omerorawclient.getServerURI().getPort(), omerorawclient.getGateway().getSessionId(omerorawclient.getGateway().getLoggedInUser()));

        return simpleClient;
    }


    /**
     * Write MeasurementTable to OMERO server. It converts it to an OMERO.table
     * and add the new table as attachment on OMERO.
     *
     * @param pathObjects
     * @param ob
     * @param qpprojName
     * @param server
     * @throws ExecutionException
     * @throws DSOutOfServiceException
     * @throws DSAccessException
     */
    public static void writeMeasurementTableData(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, String qpprojName, OmeroRawImageServer server) throws ExecutionException, DSOutOfServiceException, DSAccessException {
        //TODO: What to do if token expires?
        //TODO: What if we have more object than the limit accepted by the OMERO API?

        // get the current OMERO-RAW client
        OmeroRawClient client = server.getClient();

        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

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
        TableData omeroTable = new TableData(columns, measurements);

        // get the current image to attach the omero.table to
        ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), server.getId());

        // attach the omero.table to the image
        String type;
        if(pathObjects.iterator().next().isAnnotation())
            type = "annotation";
        else
            type = "detection";
        client.getGateway().getFacility(TablesFacility.class).addTable(client.getContext(),image,"QP "+type+" table_"+qpprojName+"_"+new Date(), omeroTable);
    }

    /**
     * Write MeasurementTable to OMERO server. It converts it to a csv file
     * and add the new file as attachment on OMERO.
     *
     * @param pathObjects
     * @param ob
     * @param qpprojName
     * @param path
     * @param server
     * @throws ExecutionException
     * @throws DSOutOfServiceException
     * @throws DSAccessException
     */
    public static void writeMeasurementTableDataAsCSV(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, String qpprojName, String path, OmeroRawImageServer server) throws ExecutionException, DSOutOfServiceException, DSAccessException {
        StringBuilder tableString = new StringBuilder();

        // get the header
        for (String col : ob.getAllNames()) {
            tableString.append(col).append(",");
        }
        tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
        tableString.append("\n");

        // get the table
        for (PathObject pathObject : pathObjects) {
            for (String col : ob.getAllNames()) {
                if (ob.isNumericMeasurement(col))
                    tableString.append(ob.getNumericValue(pathObject, col)).append(",");
                else
                    tableString.append(ob.getStringValue(pathObject, col)).append(",");
            }
            tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
            tableString.append("\n");
        }

        // get pathObject type
        String type;
        if(pathObjects.iterator().next().isAnnotation())
            type = "annotation";
        else
            type = "detection";

        // create the file locally
        File file = new File(path + File.separator + "QP " + type + " table_" + qpprojName + "_" + new Date().toString().replace(":", "-") + ".csv"); // replace ":" to be Windows compatible
        try {
           try {
               BufferedWriter buffer = new BufferedWriter(new FileWriter(file));
               buffer.write(tableString + "\n");
               buffer.close();

           } catch (IOException ex) {
               Dialogs.showErrorMessage("Write CSV file", "An error has occurred when trying to save the csv file");
           }

           // get the current OMERO-RAW client
           OmeroRawClient client = server.getClient();

           // get the current image to attach the omero.table to
           //TODO see if the try/catch statement still allows to throw error for getFacility and getImage in the parent call
           ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), server.getId());

           try {
               // attach the file to the image on OMERO
               client.getGateway().getFacility(DataManagerFacility.class).attachFile(client.getContext(), file, null, "", file.getName(), image).get();
           } catch (InterruptedException e) {
               Dialogs.showErrorMessage("Upload CSV file", "An error has occurred when trying to upload the csv file on OMERO");
               throw new RuntimeException(e);
           }
        }finally{
           // delete the temporary csv file
           if (file.exists())
               file.delete();
        }
    }

    /**
     * Write PathObject collection to OMERO server. This will delete the existing
     * ROIs present on the OMERO server if the user asked for.
     *
     */
   /* public static void writePathObjects(Collection<PathObject> pathObjects, OmeroRawImageServer server, boolean toDelete) throws ExecutionException, DSOutOfServiceException, DSAccessException {// throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
        //TODO: What to do if token expires?
        //TODO: What if we have more object than the limit accepted by the OMERO API?

        // get the current OMERO-RAW client
        OmeroRawClient client = server.getClient();

        Collection<ROIData> omeroRois = new ArrayList<>();
        Map<PathObject,String> idObjectMap = new HashMap<>();

        // create unique ID for each object
        pathObjects.forEach(pathObject -> idObjectMap.put(pathObject, pathObject.getName()));

        pathObjects.forEach(pathObject -> {
            // computes OMERO-readable ROIs
            List<ShapeData> shapes = OmeroRawShapes.convertQuPathRoiToOmeroRoi(pathObject, idObjectMap.get(pathObject), pathObject.getParent() == null ?"NoParent":idObjectMap.get(pathObject.getParent()));
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

        // delete existing ROIs on OMERO
        if(toDelete) {
            try {
                // get existing OMERO ROIs
                List<ROIResult> roiList = client.getGateway().getFacility(ROIFacility.class).loadROIs(client.getContext(), server.getId());
                List<IObject> roiData = new ArrayList<>();
                roiList.forEach(roiResult -> roiData.addAll(roiResult.getROIs().stream().map(ROIData::asIObject).collect(Collectors.toList())));

                // delete ROis
                client.getGateway().getFacility(DataManagerFacility.class).delete(client.getContext(), roiData);
                logger.info("ROIs successfully deleted");
            }catch(DSOutOfServiceException | DSAccessException | ExecutionException e){
                Dialogs.showErrorMessage("ROI Deletion","Could not delete existing ROIs on OMERO");
            }
        }

        // import ROIs on OMERO
        if (!(omeroRois.isEmpty())) {
            client.getGateway().getFacility(ROIFacility.class).saveROIs(client.getContext(), server.getId(), client.getGateway().getLoggedInUser().getId(), omeroRois);
        } else {
            Dialogs.showInfoNotification("Upload annotations","There is no Annotations to upload on OMERO");
        }
    }*/

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
            for(String st : roiCommentParsed){System.out.println(st);}
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
                idObjectMap.get(idParentIdMap.get(objID)).addPathObject(idObjectMap.get(objID));
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
     * Write metadata collection to OMERO server. This will not delete the existing
     * key-value pairs present on the OMERO server. Rather, it will simply add the new ones.
     *
     * @param qpKeyValues
     * @param imageServer
     * @throws ExecutionException
     * @throws DSOutOfServiceException
     * @throws DSAccessException
     */
    public static boolean writeMetadata(Map<String,String> qpKeyValues, OmeroRawImageServer imageServer, boolean deleteAll, boolean replace) throws ExecutionException, DSOutOfServiceException, DSAccessException {
        // read current key-value on OMERO
        List<MapAnnotationData> currentOmeroAnnotationData = readKeyValues(imageServer);

        // check unique keys
        boolean uniqueOmeroKeys = checkUniqueKeyInAnnotationMap(currentOmeroAnnotationData);

        if(!uniqueOmeroKeys)
            return false;

        // build OMERO-compatible key-value pairs
        List<NamedValue> qpNamedValues = new ArrayList<>();
        for (String key : qpKeyValues.keySet()) {
            qpNamedValues.add(new NamedValue(key, qpKeyValues.get(key)));
        }

        List<NamedValue> currentOmeroKeyValues = new ArrayList<>();
        currentOmeroAnnotationData.forEach(annotation->{
            currentOmeroKeyValues.addAll((List<NamedValue>)annotation.getContent());
        });


        // update current OMERO key-values
        qpNamedValues.forEach(pair-> {
            // check if the key-value already exists in QuPath
            for (NamedValue keyvalue : currentOmeroKeyValues) {
                if (pair.name.equals(keyvalue.name)) {
                    if(!replace)
                        pair.value = keyvalue.value;
                    currentOmeroKeyValues.remove(keyvalue);
                    break;
                }
            }
        });

        // delete all current if asked
        if(!deleteAll)
            qpNamedValues.addAll(currentOmeroKeyValues);

        // set annotation map
        MapAnnotationData omeroKeyValues = new MapAnnotationData();
        omeroKeyValues.setContent(qpNamedValues);
        omeroKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // get OMERO client
        OmeroRawClient client = imageServer.getClient();
        ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageServer.getId());

        // send key-values to OMERO
        client.getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getContext(),omeroKeyValues,imageData);

        // remove previous key-values
        client.getGateway().getFacility(DataManagerFacility.class).delete(client.getContext(),currentOmeroAnnotationData.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()));

        return true;
    }

    /**
     * Read Key Values from OMERO server.
     *
     * Code partially taken from Pierre Pouchin, from his simple-omero-client project
     *
     * @param imageServer
     * @throws ExecutionException
     * @throws DSOutOfServiceException
     * @throws DSAccessException
     */
    public static List<MapAnnotationData> readKeyValues(OmeroRawImageServer imageServer) throws ExecutionException, DSOutOfServiceException, DSAccessException {

        // read key-values from OMERO
        OmeroRawClient client = imageServer.getClient();
        ImageData imageData = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageServer.getId());
        List<AnnotationData> annotations = client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), imageData);

        return annotations.stream()
                .filter(MapAnnotationData.class::isInstance)
                .map(MapAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    public static boolean writeOmeroROIs(OmeroRawClient client, long imageId, List<ROIData> omeroRois, boolean toDelete) {
        boolean roiSaved = false;

        // delete existing ROIs on OMERO
        if(toDelete) {
            try {
                // get existing OMERO ROIs
                List<ROIResult> roiList = client.getGateway().getFacility(ROIFacility.class).loadROIs(client.getContext(), imageId);

                // extract ROIData
                List<IObject> roiData = new ArrayList<>();
                roiList.forEach(roiResult -> roiData.addAll(roiResult.getROIs().stream().map(ROIData::asIObject).collect(Collectors.toList())));

                // delete ROis
                client.getGateway().getFacility(DataManagerFacility.class).delete(client.getContext(), roiData);

                Dialogs.showInfoNotification("ROI deletion","ROIs successfully deleted");
            } catch (DSOutOfServiceException | DSAccessException | ExecutionException e){
                Dialogs.showErrorMessage("ROI deletion","Could not delete existing ROIs on OMERO.");
                logger.error("" + e);
                throw new RuntimeException(e);
            }
        }

        // import ROIs on OMERO
        if (!(omeroRois.isEmpty())) {
            try {
                // save ROIs
                client.getGateway().getFacility(ROIFacility.class).saveROIs(client.getContext(), imageId, client.getGateway().getLoggedInUser().getId(), omeroRois);
                roiSaved = true;
            } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
                Dialogs.showErrorMessage("ROI Saving","Error during saving ROIs on OMERO.");
                logger.error("" + e);
                throw new RuntimeException(e);
            }

        } else {
            Dialogs.showInfoNotification("Upload annotations","There is no Annotations to upload on OMERO");
        }

        return roiSaved;
    }


    public static List<ROIData> readOmeroROIs(OmeroRawClient client, long imageId){
        List<ROIResult> roiList;

        // get ROIs from OMERO
        try {
            roiList = client.getGateway().getFacility(ROIFacility.class).loadROIs(client.getContext(), imageId);
        } catch (DSOutOfServiceException | DSAccessException | ExecutionException e) {
            throw new RuntimeException(e);
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
     * Try to solve an error in OMERO regarding the creation keys.
     * On OMERO, it is possible to have two identical keys with a different value. This should normally never append.
     * This method check if all keys are unique and output false if there is at least two identical keys.
     *
     * @param annotationDataList
     * @return
     */
    public static boolean checkUniqueKeyInAnnotationMap(List<MapAnnotationData> annotationDataList){
        boolean uniqueKey = true;

        List<NamedValue> keyValues = new ArrayList<>();
        annotationDataList.forEach(annotation->{
            keyValues.addAll((List<NamedValue>)annotation.getContent());
        });

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

        Pixels pixel = null;
        try {
            ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), imageId);
            pixel = image.asImage().getPixels(0);
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e){
            Dialogs.showErrorMessage( "Error retrieving image and pixels for thumbnail :","" +e);
            return null;
        }

        int   sizeX  = pixel.getSizeX().getValue();
        int   sizeY  = pixel.getSizeY().getValue();
        float ratioX = (float) sizeX / prefSize;
        float ratioY = (float) sizeY / prefSize;
        float ratio  = Math.max(ratioX, ratioY);
        int   width  = (int) (sizeX / ratio);
        int   height = (int) (sizeY / ratio);

        BufferedImage thumbnail = null;
        byte[] array = null;
        try {
            ThumbnailStorePrx store = client.getGateway().getThumbnailService(client.getContext());
            store.setPixelsId(pixel.getId().getValue());
            array = store.getThumbnail(rint(width), rint(height));
            store.close();
        } catch (DSOutOfServiceException | ServerError e) {
            Dialogs.showErrorMessage( "Error retrieving thumbnail :","" +e);
        }

        if (array != null) {
            try (ByteArrayInputStream stream = new ByteArrayInputStream(array)) {
                //Create a buffered image to display
                thumbnail = ImageIO.read(stream);
            }catch(IOException e){
                Dialogs.showErrorMessage( "Error converting thumbnail to bufferedImage :","" +e);
            }
        }

        return thumbnail;
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
     * <li> If the URI does not contain a host (but does a path), it will be returned without modification. </li>
     * <li> If no host <b>and</b> no path is found, {@code null} is returned. </li>
     * <li> If the specified {@code uri} does not contain a scheme, {@code https://} will be used. </li>
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
            logger.error("Could not parse server from {}: {}", uri.toString(), ex.getLocalizedMessage());
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
        throw new IOException("URI not recognized: " + uri.toString());
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
            throw new IOException("URI not recognized: " + uri.toString());

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
                    logger.info("No image found in URI: " + uri.toString());
                for (int i = 0; i < ids.size(); i++) {
                    String imgId = (i == ids.size()-1) ? ids.get(i) : ids.get(i) + vertBarSign + "image-";
                    sb.append(imgId);
                }
                break;
            default:
                throw new IOException("No image found in URI: " + uri.toString());
        }

        return URI.create(sb.toString());
    }

    static Node createStateNode(boolean loggedIn) {
        var state = loggedIn ? IconFactory.PathIcons.ACTIVE_SERVER : IconFactory.PathIcons.INACTIVE_SERVER;
        return IconFactory.createNode(QuPathGUI.TOOLBAR_ICON_SIZE, QuPathGUI.TOOLBAR_ICON_SIZE, state);
    }
}
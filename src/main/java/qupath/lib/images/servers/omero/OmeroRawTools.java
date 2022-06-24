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

package qupath.lib.images.servers.omero;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.api.client.json.Json;
import omero.RLong;
import omero.ServerError;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.model.*;
import omero.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javafx.application.Platform;
import javafx.scene.Node;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.images.servers.omero.OmeroRawAnnotations.OmeroRawAnnotationType;
//import qupath.lib.images.servers.omero.OmeroObjects.OmeroObject;
//import qupath.lib.images.servers.omero.OmeroObjects.OmeroObjectType;
//import qupath.lib.images.servers.omero.OmeroObjects.OrphanedFolder;
//import qupath.lib.images.servers.omero.OmeroObjects.Server;
import qupath.lib.images.servers.omero.OmeroRawObjects.OmeroRawObject;
import qupath.lib.images.servers.omero.OmeroRawObjects.Server;
import qupath.lib.images.servers.omero.OmeroRawObjects.Owner;
import qupath.lib.images.servers.omero.OmeroRawObjects.Group;
import qupath.lib.images.servers.omero.OmeroRawObjects.OrphanedFolder;
import qupath.lib.images.servers.omero.OmeroRawObjects.OmeroRawObjectType;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;


/**
 * Static helper methods related to OMEROWebImageServer.
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

    /**
     * Suppress default constructor for non-instantiability
     */
    private OmeroRawTools() {
        throw new AssertionError();
    }

    /**
     * Return the web client used for the specified OMERO server.
     *
     * @param server
     * @return client
     */
    public static OmeroWebClient getWebclient(OmeroWebImageServer server) {
        return server.getWebclient();
    }


    /**
     * Get all the OMERO objects (inside the parent Id) present in the OMERO server with the specified
     * URI.
     * <p>
     * No orphaned {@code OmeroObject} will be fetched.
     *
     * @param client
     * @param parent
     * @return list of OmeroObjects
     * @throws IOException
     */
    public static List<OmeroRawObject> readOmeroObjects(OmeroRawObject parent, OmeroRawClient client, SecurityContext groupCtx, Group group) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
        List<OmeroRawObject> list = new ArrayList<>();
        if (parent == null)
            return list;
        //System.out.println("In readOmeroObjects");
        //System.out.println("Parent object : " +parent);
        //System.out.println("Parent object type : " +parent.getType());

        OmeroRawObjectType type = OmeroRawObjectType.PROJECT;
        if (parent.getType() == OmeroRawObjectType.PROJECT)
            type = OmeroRawObjectType.DATASET;
        else if (parent.getType() == OmeroRawObjectType.DATASET)
            type = OmeroRawObjectType.IMAGE;

        final OmeroRawObjectType finaltype = type;

        try {
            if (type == OmeroRawObjectType.PROJECT) {
                //System.out.println("OmeroRawTools-readOmeroObjects---->Type : Project");

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
               // System.out.println("Size of projects : " +list.size());
            }
            else if (type == OmeroRawObjectType.DATASET) {
               // System.out.println("OmeroRawTools-readOmeroObjects---->Type : Dataset");

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
                    //System.out.println("OmeroRawTools-readOmeroObjects---->List of datasets : " +list);
                }



            }
            else if (type == OmeroRawObjectType.IMAGE) {
               // System.out.println("OmeroRawTools-readOmeroObjects---->Type : Image");

                // get the current dataset to have access to the child images
                Collection<DatasetData> datasetColl = client.getGateway().getFacility(BrowseFacility.class).getDatasets(groupCtx,Collections.singletonList(parent.getId()));
                //System.out.println("OmeroRawTools-readOmeroObjects---->Size of linked images : "+datasetColl.iterator().next().asDataset().sizeOfImageLinks());
                if(datasetColl.iterator().next().asDataset().sizeOfImageLinks() > 0){
                    List<ImageData> images = new ArrayList<>();
                    List<DatasetImageLink> links = datasetColl.iterator().next().asDataset().copyImageLinks();

                    // get child images
                    for (DatasetImageLink link : links) {
                        images.add(new ImageData(link.getChild()));
                    }

                    // create OmeroRawObjects from child images
                    //System.out.println("OmeroRawTools-readOmeroObjects---->images list : "+images);
                    images.forEach(e-> {
                        try {
                            list.add(new OmeroRawObjects.Image("",e,e.getId(),finaltype,client,parent));
                        } catch (DSOutOfServiceException | ServerError ex) {
                            throw new RuntimeException(ex);
                        }
                    });
                    //System.out.println("OmeroRawTools-readOmeroObjects---->List of images : " +list);
                }
            }
        } catch (DSOutOfServiceException | DSAccessException e) {
            throw new IOException("Cannot get datasets");//handleServiceOrAccess(e, "Cannot get datasets");
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
    public static Owner getDefaultOwner(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        Experimenter user = client.getGateway().getAdminService(client.getContext()).getExperimenter(client.getGateway().getLoggedInUser().getId());
        return new Owner(user.getId()==null ? 0 : user.getId().getValue(),
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
    public static Group getDefaultGroup(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        ExperimenterGroup userGroup = client.getGateway().getAdminService(client.getContext()).getDefaultGroup(client.getGateway().getLoggedInUser().getId());
        return new Group(userGroup.getId().getValue(), userGroup.getName().getValue());
    }

    /**
     * return a map of available groups with its attached users.
     * @param client
     * @return
     * @throws DSOutOfServiceException
     * @throws ServerError
     */

    public static Map<Group,List<Owner>> getAvailableGroups(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        Map<Group,List<Owner>> map = new HashMap<>();

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

            List<Owner> owners = new ArrayList<>();
            Group userGroup = new Group(group.getId().getValue(), group.getName().getValue());

            for (Experimenter user : users) {

                owners.add(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                        user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                        user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                        user.getLastName()==null ? "" : user.getLastName().getValue(),
                        user.getEmail()==null ? "" : user.getEmail().getValue(),
                        user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                        user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            }
            map.put(userGroup, owners);

        });

        return map;
    }


    /**
     * Populate the specified {@code orphanedFolder}'s image list with all orphaned images in the server.
     * <p>
     * As soon as all the objects have been loaded in the list, the {@code isLoading} property of the
     * {@code OrphanedFodler} is modified accordingly.
     *
     * @param client
     * @param orphanedFolder
     */
   /* public static synchronized void populateOrphanedImageList(OmeroRawClient client, OrphanedFolder orphanedFolder) throws DSOutOfServiceException, ExecutionException, DSAccessException, IOException, ServerError {
        var list = orphanedFolder.getImageList();
        orphanedFolder.setLoading(false);
        list.clear();

       /System.out.println("OmeroRawToools - populateOrphanedImageList - Begin getOrphanedImages");
        long time = System.currentTimeMillis();
        Collection<ImageData> map = OmeroRawRequests.getOrphanedImages(client);
        System.out.println("OmeroRawToools - populateOrphanedImageList - End getOrphanedImages : "+(System.currentTimeMillis()-time));

        ExecutorService executorRequests = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("orphaned-image-requests", true));

        // Get the total amount of orphaned images to load
        int max = map.size();
        if (max == 0)
            orphanedFolder.setLoading(false);

        orphanedFolder.setTotalChildCount(max);
        map.forEach( e -> {
        ExecutorService executorRequests = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("orphaned-image-requests", true));

        executorRequests.submit(() -> {
                try {
                   // System.out.println("OmeroRawToools - populateOrphanedImageList - Begin getOrphanedImages");
                    //long time = System.currentTimeMillis();
                    Collection<ImageData> map = OmeroRawRequests.getOrphanedImages(client, null);
                   // System.out.println("OmeroRawToools - populateOrphanedImageList - End getOrphanedImages : "+(System.currentTimeMillis()-time));


                    // Get the total amount of orphaned images to load
                    int max = map.size();
                    if (max == 0)
                        orphanedFolder.setLoading(false);

                    orphanedFolder.setTotalChildCount(max);
                    Platform.runLater(() -> {
                        map.forEach( e -> {

                        OmeroRawObject omeroObj = null;
                        try {
                            omeroObj = new OmeroRawObjects.Image("", e, e.getId(), OmeroRawObjectType.IMAGE, client, new Server(client.getServerURI()));
                        } catch (DSOutOfServiceException | ServerError ex) {
                            throw new RuntimeException(ex);
                        }

                            list.add(omeroObj);

                            // Check if all orphaned images were loaded
                            if (orphanedFolder.incrementAndGetLoadedCount() >= max)
                                orphanedFolder.setLoading(false);
                        });
                    });
                } catch (DSOutOfServiceException | ServerError ex) {
                    throw new RuntimeException(ex);
                }

        });
        executorRequests.shutdown();
    }*/

    /**
     * Get all the orphaned {@code OmeroObject}s of type {@code type} from the server.
     *
     * @param client the client {@code OmeroRawClient} object
     * @return list of orphaned datasets
     * @throws IOException
     */
    public static List<OmeroRawObject> readOrphanedDatasets(OmeroRawClient client, SecurityContext groupCtx) throws IOException, ServerError, DSOutOfServiceException {
        List<OmeroRawObject> list = new ArrayList<>();
        Collection<DatasetData> orphanedDatasets = OmeroRawRequests.getOrphanedDatasets(client,groupCtx);

        orphanedDatasets.forEach( e -> {
            OmeroRawObject omeroObj;
            try {
                omeroObj = new OmeroRawObjects.Dataset("", e, e.getId(), OmeroRawObjectType.DATASET, client, new Server(client.getServerURI()));
                list.add(omeroObj);
            } catch (DSOutOfServiceException | ServerError ex) {
                throw new RuntimeException(ex);
            }

        });

        return list;
    }

    public static List<OmeroRawObject> readOrphanedImages(OmeroRawClient client, SecurityContext groupCtx) throws IOException, ServerError, DSOutOfServiceException {
        List<OmeroRawObject> list = new ArrayList<>();
        Collection<ImageData> orphanedImages = OmeroRawRequests.getOrphanedImages(client,groupCtx);

        orphanedImages.forEach( e -> {
            OmeroRawObject omeroObj;
            try {
                omeroObj = new OmeroRawObjects.Image("", e, e.getId(), OmeroRawObjectType.IMAGE, client, new Server(client.getServerURI()));
                list.add(omeroObj);
            } catch (DSOutOfServiceException | ServerError ex) {
                throw new RuntimeException(ex);
            }

        });

        return list;
    }

    /**
     * Helper method to retrieve an {@code OmeroObject} of type {@code OmeroObjectType} with {@code id} from
     * a given server details. An IOException will be thrown if the connection fails.
     * <p>
     * N.B: this method does not set the parent object.
     *
     * @param client
     * @param obj
     * @param type
     * @return OmeroObject
     * @throws IOException
     */
   /* public static OmeroRawObject readOmeroObject(OmeroRawClient client, OmeroRawObject obj, OmeroRawObjectType type) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {

        // TODO Only used for orphan images ; find a way to integrate it in the readOmeroObjects method
        Server parent = new Server(client.getServerURI());
        SecurityContext ctx = new SecurityContext(obj.getGroup().getId());
        int id = 0; // To remove-> just to conpile

        try {
            switch (type) {
                case IMAGE:
                   // ImageData image = client.getGateway().getFacility(BrowseFacility.class).getImage(ctx, id);
                   // return new OmeroRawObjects.Image("",image,id,type,client, parent);
                case DATASET:
                    //Collection<DatasetData> datasets = client.getGateway().getFacility(BrowseFacility.class).getDatasets(ctx, Collections.singletonList(id));
                   // return new OmeroRawObjects.Dataset("",datasets.iterator().next(),id,type,client,parent);
                case PROJECT:
                    Collection<ProjectData> projects = client.getGateway().getFacility(BrowseFacility.class).getProjects(client.getContext(), Collections.singletonList(id));
                    ProjectData project = projects.iterator().next();
                    OmeroRawObjects.Project objProject = new OmeroRawObjects.Project("",project,id,type,client, parent);
                    return objProject;
                default:
                    throw new UnsupportedOperationException("Type (" + type + ") not supported");
            }
        }catch(Exception e){
            throw new IOException(String.format("Cannot have access to %s with id %d.", type, id));
        }
    }*/

    /**
     * Return the Id associated with the {@code URI} provided.
     * If multiple Ids are present, only the first one will be retrieved.
     * If no Id could be found, return -1.
     *
     * @param uri
     * @param type
     * @return Id
     */
    public static int parseOmeroObjectId(URI uri, OmeroRawObjectType type) {
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
     * Accepts the same formats as the {@code OmeroWebImageServer} constructor.
     * <br>
     * E.g., https://{server}/webclient/?show=dataset-{datasetId}
     *
     * @param uri
     * @return omeroObjectType
     */
    public static OmeroRawObjectType parseOmeroObjectType(URI uri) {
        var uriString = uri.toString().replace("%3D", "=");
        if (patternLinkProject.matcher(uriString).find())
            return OmeroRawObjectType.PROJECT;
        else if (patternLinkDataset.matcher(uriString).find())
            return OmeroRawObjectType.DATASET;
        else {
            for (var p: imagePatterns) {
                if (p.matcher(uriString).find())
                    return OmeroRawObjectType.IMAGE;
            }
        }
        return OmeroRawObjectType.UNKNOWN;
    }


    /**
     * Request the {@code OmeroAnnotations} object of type {@code category} associated with
     * the {@code OmeroObject} specified.
     *
     * @param client
     * @param obj
     * @param category
     * @return omeroAnnotations object
     */
    public static OmeroRawAnnotations readOmeroAnnotations(OmeroRawClient client, OmeroRawObject obj, OmeroRawAnnotationType category) {
        try {
            List<?> annotations = client.getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getContext(), obj.getData());
            return OmeroRawAnnotations.getOmeroAnnotations(client, category, annotations);
        } catch (Exception ex) {
            logger.warn("Could not fetch {} information: {}", category, ex.getLocalizedMessage());
            return null;
        }
    }

    /**
     * Write PathObject collection to OMERO server. This will not delete the existing
     * ROIs present on the OMERO server. Rather, it will simply add the new ones.
     *
     * @param pathObjects
     * @param server
     * @return success
     * @throws IOException
     */
    public static boolean writePathObjects(Collection<PathObject> pathObjects, OmeroRawImageServer server) throws IOException {
        // TODO: What to do if token expires?
        // TODO: What if we have more object than the limit accepted by the OMERO API?
        if (pathObjects.isEmpty())
            return true;

        String id = server.getId().toString();
        String host = server.getHost();
        String scheme = server.getScheme();
        int port = server.getPort();

        // TODO: probably should do this in one line
        Gson gsonTMAs  = new GsonBuilder().registerTypeAdapter(TMACoreObject.class, new OmeroShapes.GsonShapeSerializer()).serializeSpecialFloatingPointValues().setLenient().create();
        Gson gsonAnnotation = new GsonBuilder().registerTypeAdapter(PathAnnotationObject.class, new OmeroShapes.GsonShapeSerializer()).setLenient().create();
        Gson gsonDetection  = new GsonBuilder().registerTypeAdapter(PathDetectionObject.class, new OmeroShapes.GsonShapeSerializer()).serializeSpecialFloatingPointValues().setLenient().create();

        // Iterate through PathObjects and get their JSON representation
        List<String> jsonList = new ArrayList<>();
        for (var pathObject: pathObjects) {
            String myJson = "";
            if (pathObject.isTMACore())
                myJson = gsonTMAs.toJson(pathObject);
            else if (pathObject.isAnnotation())
                myJson = gsonAnnotation.toJson(pathObject);
            else if (pathObject.isDetection()) {
                // TODO: ugly design, should improve this
                if (pathObject instanceof PathCellObject) {
                    var detTemp = PathObjects.createDetectionObject(pathObject.getROI());
                    detTemp.setPathClass(pathObject.getPathClass());
                    detTemp.setColorRGB(pathObject.getColorRGB());
                    detTemp.setName(pathObject.getName());
                    pathObject = detTemp;
                }
                myJson = gsonDetection.toJson(pathObject);
            } else
                throw new IOException(String.format("Type not supported: %s", pathObject.getClass()));

            try {
                // See if resulting JSON is a list (e.g. Points/MultiPolygon)
                List<JsonElement> roiList = Arrays.asList(GsonTools.getInstance().fromJson(myJson, JsonElement[].class));
                roiList.forEach(e -> jsonList.add(e.toString()));
            } catch (Exception ex) {
                jsonList.add(myJson);
            }
        }

        return false;//OmeroRequests.requestWriteROIs(scheme, host, port, Integer.parseInt(id), server.getClient().getToken(), jsonList);
    }

    /**
     * Return the thumbnail of the OMERO image corresponding to the specified {@code imageId}.
     *
     * @param server
     * @param imageId
     * @param prefSize
     * @return thumbnail
     */
    public static BufferedImage getThumbnail(OmeroWebImageServer server, long imageId, int prefSize) {
        //try {
            return null;//OmeroRequests.requestThumbnail(server.getScheme(), server.getHost(), server.getPort(), imageId, prefSize);
        /*} catch (IOException ex) {
            logger.warn("Error requesting the thumbnail: {}", ex.getLocalizedMessage());
            return null;
        }*/
    }

    /**
     * Return the thumbnail of the OMERO image corresponding to the specified {@code id}.
     *
     * @param uri
     * @param id
     * @param prefSize
     * @return thumbnail
     */
    public static BufferedImage getThumbnail(URI uri, OmeroRawClient client, long id, int prefSize) {
        /*try {
            return OmeroRequests.requestThumbnail(uri.getScheme(), uri.getHost(), uri.getPort(), id, prefSize);
        } catch (IOException ex) {
            logger.warn("Error requesting the thumbnail: {}", ex.getLocalizedMessage());
            return null;
        }*/

        return null;
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
        OmeroRawObjectType type;
        String query = uri.getQuery() != null ? uri.getQuery() : "";

        // Because of encoding, the equal sign might not be recognized when loading .qpproj file
        query = query.replace("%3D", "=");

        // Match
        var matcherType = patternType.matcher(query);
        if (matcherType.find())
            type = OmeroRawObjectType.fromString(matcherType.group(1).replace("-", ""));
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
                type = OmeroRawObjectType.DATASET;

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
                type = OmeroRawObjectType.IMAGE;

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
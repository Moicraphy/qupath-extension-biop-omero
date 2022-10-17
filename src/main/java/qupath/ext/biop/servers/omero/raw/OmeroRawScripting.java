package qupath.ext.biop.servers.omero.raw;

import fr.igred.omero.Client;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ROIData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.scripting.QP;

import java.util.Collection;
import java.util.Date;
import java.util.List;

public class OmeroRawScripting {

    /**
     * This method creates an instance of {@code fr.igred.omero.Client} object to get access to the full
     * simple-omero-client API, developed by Pierre Pouchin (https://github.com/GReD-Clermont/simple-omero-client).
     *
     * @return the Client object
     */
    public static Client getSimpleOmeroClientInstance(OmeroRawImageServer server) throws DSOutOfServiceException {
        // get the current OmeroRawClient
        OmeroRawClient omerorawclient = server.getClient();

        // build the simple-omero-client using the ID of the current session
        Client simpleClient = new Client();
        simpleClient.connect(omerorawclient.getServerURI().getHost(), omerorawclient.getServerURI().getPort(), omerorawclient.getGateway().getSessionId(omerorawclient.getGateway().getLoggedInUser()));

        return simpleClient;
    }


    /**
     * Read ROIs from OMERO and add them to the current image
     *
     * @param imageServer
     * @return
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer){
        return importOmeroROIsToQuPath(imageServer,true);
    }

    /**
     * Read ROIs from OMERO and add them to the current image
     *
     * @param imageServer
     * @return
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removeAnnotations){
        // read OMERO ROIs
        Collection<PathObject> pathObjects = imageServer.readPathObjects();

        // get the current hierarchy
        PathObjectHierarchy hierarchy = QP.getCurrentHierarchy();

        // remove current annotations
        if(removeAnnotations)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(),false);

        // add pathObjects to the current hierarchy
        if(!pathObjects.isEmpty()) {
            hierarchy.addPathObjects(pathObjects);
            hierarchy.resolveHierarchy();
        }

        return pathObjects;
    }

    /**
     * Send all pathObjects (annotations and detections) to OMERO and deletes ROIs already stored on OMERO
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer
     * @return
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer){
        return sendPathObjectsToOmero(imageServer, true);
    }

    /**
     * Send all pathObjects (annotations and detections) to OMERO. The user can choose
     * if he wants to delete existing ROIs or not.
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO){
        Collection<PathObject> pathObjects = QP.getAnnotationObjects();
        pathObjects.addAll(QP.getDetectionObjects());
        return sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO);
    }

    /**
     * Send all annotations to OMERO without deleting existing ROIs.
     *
     * @param imageServer
     * @return
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer){
        return sendAnnotationsToOmero(imageServer,false);
    }


    /**
     * Send all annotations to OMERO. The user can choose if he wants to delete existing ROIs or not.
     *
     * @param imageServer
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO){
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIsOnOMERO);
    }

    /**
     * Send all detections to OMERO without deleting existing ROIs.
     *
     * @param imageServer
     * @return
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer){
        return sendDetectionsToOmero(imageServer, false);
    }

    /**
     * Send all detections to OMERO. The user can choose if he wants to delete existing ROIs or not.
     *
     * @param imageServer
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO){
        Collection<PathObject> detections = QP.getDetectionObjects();
        return sendPathObjectsToOmero(imageServer, detections, deleteROIsOnOMERO);
    }

    /**
     * Send a collection of PathObjects to OMERO, without deleting existing ROIs
     *
     * @param imageServer
     * @param annotations
     * @return
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> annotations){
        return sendPathObjectsToOmero(imageServer, annotations,false);
    }

    /**
     * Send a collection of pathObjects to OMERO.
     * The user can choose if he wants to delete existing ROIs or not.
     *
     * @param imageServer
     * @param pathObjects
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects, boolean deleteROIsOnOMERO){
        // set pathObjectName with a unique ID
        //TODO remove this unique id in qupath 0.4.0
        pathObjects.forEach(pathObject -> pathObject.setName(""+ (new Date()).getTime() + pathObject.hashCode()));
        // convert pathObjects to OMERO ROIs
        List<ROIData> omeroROIs = OmeroRawTools.createOmeroROIsFromPathObjects(pathObjects);
        // set pathObjectName to null to not interfere with qupath display
        pathObjects.forEach(pathObject -> pathObject.setName(null));

        // get omero client and image id to send ROIs to the correct image
        OmeroRawClient client = imageServer.getClient();
        long imageId = imageServer.getId();

        // delete ROIs
        if(deleteROIsOnOMERO)
            OmeroRawTools.deleteOmeroROIs(client,imageId);

        // send to OMERO
        return OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);
    }


}

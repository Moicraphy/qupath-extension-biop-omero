package qupath.ext.biop.servers.omero.raw;

import fr.igred.omero.Client;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.ROIData;
import omero.model.NamedValue;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.Collectors;

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
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer) {
        return importOmeroROIsToQuPath(imageServer, true);
    }

    /**
     * Read ROIs from OMERO and add them to the current image
     *
     * @param imageServer
     * @return
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removeAnnotations) {
        // read OMERO ROIs
        Collection<PathObject> pathObjects = imageServer.readPathObjects();

        // get the current hierarchy
        PathObjectHierarchy hierarchy = QP.getCurrentHierarchy();

        // remove current annotations
        if (removeAnnotations)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(), false);

        // add pathObjects to the current hierarchy
        if (!pathObjects.isEmpty()) {
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
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer) {
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
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
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
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer) {
        return sendAnnotationsToOmero(imageServer, false);
    }


    /**
     * Send all annotations to OMERO. The user can choose if he wants to delete existing ROIs or not.
     *
     * @param imageServer
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIsOnOMERO);
    }

    /**
     * Send all detections to OMERO without deleting existing ROIs.
     *
     * @param imageServer
     * @return
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer) {
        return sendDetectionsToOmero(imageServer, false);
    }

    /**
     * Send all detections to OMERO. The user can choose if he wants to delete existing ROIs or not.
     *
     * @param imageServer
     * @param deleteROIsOnOMERO
     * @return
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
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
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> annotations) {
        return sendPathObjectsToOmero(imageServer, annotations, false);
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
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects, boolean deleteROIsOnOMERO) {
        // set pathObjectName with a unique ID
        //TODO remove this unique id in qupath 0.4.0
        pathObjects.forEach(pathObject -> pathObject.setName("" + (new Date()).getTime() + pathObject.hashCode()));
        // convert pathObjects to OMERO ROIs
        List<ROIData> omeroROIs = OmeroRawTools.createOmeroROIsFromPathObjects(pathObjects);
        // set pathObjectName to null to not interfere with qupath display
        pathObjects.forEach(pathObject -> pathObject.setName(null));

        // get omero client and image id to send ROIs to the correct image
        OmeroRawClient client = imageServer.getClient();
        long imageId = imageServer.getId();

        // delete ROIs
        if (deleteROIsOnOMERO)
            OmeroRawTools.deleteOmeroROIs(client, imageId);

        // send to OMERO
        return OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);
    }


    public static boolean saveMetadataOnOmero(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        Map<String,String> omeroKeyValuePairs = getOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return false;

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.filterExistingKeyValues(omeroKeyValuePairs, qpMetadata);
        Map<String,String>  newKV = splitKeyValues.get(1);

        System.out.println(newKV);

        // convert key value pairs to omero-compatible format
        List<NamedValue> newNV = new ArrayList<>();
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        if(newNV.isEmpty()) {
            Dialogs.showInfoNotification("Save metadata on OMERO", "All metadata already exist on OMERO");
            return false;
        }

        // set annotation map
        MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
        newOmeroAnnotationMap.setContent(newNV);
        newOmeroAnnotationMap.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());

        return true;
    }

    public static void saveMetadataOnOmeroAndDeleteKeyValues(Map<String, String> qpKeyValues, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        Map<String,String> omeroKeyValuePairs = getOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return;

        // convert key value pairs to omero-compatible format
        List<NamedValue> newNV = new ArrayList<>();
        qpKeyValues.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // set annotation map
        MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
        newOmeroAnnotationMap.setContent(newNV);
        newOmeroAnnotationMap.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // get the current keyValue On OMERO
        OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());

        // delete current keyValues
        OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());
    }

    public static boolean saveMetadataAndUpdateKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        Map<String,String> omeroKeyValuePairs = getOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return false;

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.filterExistingKeyValues(omeroKeyValuePairs, qpMetadata);
        Map<String,String>  newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);

        omeroKeyValuePairs.forEach((keyToUpdate, valueToUpdate) -> {
            for (String updated : existingKV.keySet())
                if (keyToUpdate.equals(updated))
                    omeroKeyValuePairs.replace(keyToUpdate, valueToUpdate, existingKV.get(keyToUpdate));
        });

        // convert key value pairs to omero-compatible format
        List<NamedValue> newNV = new ArrayList<>();
        omeroKeyValuePairs.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // set annotation map
        MapAnnotationData omeroKeyValues = new MapAnnotationData();
        omeroKeyValues.setContent(newNV);
        omeroKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // get the current keyValue On OMERO
        OmeroRawTools.addKeyValuesOnOmero(omeroKeyValues, imageServer.getClient(), imageServer.getId());

        // delete current keyValues
        OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());

        return true;
    }


    public static boolean addMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());


        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.filterExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);

        newKV.forEach(entry::putMetadataValue);
        return true;
    }

    public static boolean addOmeroKeyValues(OmeroRawImageServer imageServer) {
        Map<String,String> omeroKeyValuePairs = getOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return false;

        return addMetadata(omeroKeyValuePairs);
    }


    public static boolean addAndUpdateMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.filterExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);
        Map<String,String> updatedKV = new HashMap<>();

        qpMetadata.forEach((keyToUpdate, valueToUpdate) -> {
            String newValue = valueToUpdate;
            for (String updated : existingKV.keySet()) {
                if (keyToUpdate.equals(updated)) {
                    newValue = existingKV.get(keyToUpdate);
                    break;
                }
            }
            updatedKV.put(keyToUpdate, newValue);
        });

        // delete metadata
        entry.clearMetadata();

        updatedKV.forEach(entry::putMetadataValue);
        newKV.forEach(entry::putMetadataValue);

        return true;
    }

    public static boolean addOmeroKeyValuesAndUpdateMetadata(OmeroRawImageServer imageServer) {
        Map<String,String> omeroKeyValuePairs = getOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return false;

        return addAndUpdateMetadata(omeroKeyValuePairs);
    }

    public static boolean addOmeroKeyValuesAndDeleteMetadata(OmeroRawImageServer imageServer) {
        Map<String,String> omeroKeyValues = getOmeroKeyValues(imageServer);

        if(omeroKeyValues == null || omeroKeyValues.isEmpty())
            return false;

        return addAndDeleteMetadata(omeroKeyValues);
    }

    public static boolean addAndDeleteMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());

        // delete metadata
        entry.clearMetadata();

        // add new metadata
        keyValues.forEach(entry::putMetadataValue);

        return true;
    }

    public static Map<String,String> getOmeroKeyValues(OmeroRawImageServer imageServer) {
        // read current key-value on OMERO
        List<NamedValue> currentOmeroKeyValues = OmeroRawTools.readKeyValuesAsNamedValue(imageServer.getClient(), imageServer.getId());

        if (currentOmeroKeyValues.isEmpty()) {
            Dialogs.showWarningNotification("Read key values on OMERO", "The current image does not have any KeyValues on OMERO");
            return null;
        }

        // check unique keys
        boolean uniqueOmeroKeys = OmeroRawTools.checkUniqueKeyInAnnotationMap(currentOmeroKeyValues);

        if (!uniqueOmeroKeys) {
            Dialogs.showErrorMessage("Keys not unique", "There are at least two identical keys on OMERO. Please make each key unique");
            return null;
        }

        return currentOmeroKeyValues.stream().collect(Collectors.toMap(e->e.name, e->e.value));
    }

}
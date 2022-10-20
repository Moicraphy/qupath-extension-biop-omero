package qupath.ext.biop.servers.omero.raw;

//import fr.igred.omero.Client;
//import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.ROIData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.model.NamedValue;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class OmeroRawScripting {

    /**
     * This method creates an instance of {@code fr.igred.omero.Client} object to get access to the full
     * simple-omero-client API, developed by Pierre Pouchin (https://github.com/GReD-Clermont/simple-omero-client).
     *
     * @return the Client object
     */
    /*public static Client getSimpleOmeroClientInstance(OmeroRawImageServer server) throws DSOutOfServiceException {
        // get the current OmeroRawClient
        OmeroRawClient omerorawclient = server.getClient();

        // build the simple-omero-client using the ID of the current session
        Client simpleClient = new Client();
        simpleClient.connect(omerorawclient.getServerURI().getHost(), omerorawclient.getServerURI().getPort(), omerorawclient.getGateway().getSessionId(omerorawclient.getGateway().getLoggedInUser()));

        return simpleClient;
    }*/


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


    /**
     * Save on OMERO some key-values contained in a Map. It does not delete any of the existing key value pairs.
     * Moreover, if keys already exist on OMERO, it does not update them neither adding the new ones.
     *
     * @param qpMetadata
     * @param imageServer
     * @return
     */
    public static boolean sendMetadataOnOmero(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(omeroKeyValuePairs, qpMetadata);
        Map<String,String>  newKV = splitKeyValues.get(1);

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // if all keys already exist, do not write an empty list
        if(newNV.isEmpty()) {
            Dialogs.showInfoNotification("Save metadata on OMERO", "All metadata already exist on OMERO");
            return false;
        }

        // set annotation map
        MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
        newOmeroAnnotationMap.setContent(newNV);
        newOmeroAnnotationMap.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // add key value on OMERO
        return OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());
    }

    /**
     * Delete all existing key value pairs attach to the current image on OMERO
     * and save some new key-values contained in the Map.
     *
     * @param qpMetadata
     * @param imageServer
     */
    public static boolean sendMetadataOnOmeroAndDeleteKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        qpMetadata.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // set annotation map
        MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
        newOmeroAnnotationMap.setContent(newNV);
        newOmeroAnnotationMap.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // add key value pairs On OMERO
        boolean wasAdded = OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());

        // delete current keyValues
        boolean wasDeleted = OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());

        return (wasDeleted && wasAdded);
    }


    /**
     * Update existing key value pairs on OMERO with new values from the metadata and save new ones.
     *
     * @param qpMetadata
     * @param imageServer
     * @return
     */
    public static boolean sendMetadataOnOmeroAndUpdateKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(omeroKeyValuePairs, qpMetadata);
        Map<String,String>  newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);

        omeroKeyValuePairs.forEach((keyToUpdate, valueToUpdate) -> {
            for (String updated : existingKV.keySet())
                if (keyToUpdate.equals(updated))
                    omeroKeyValuePairs.replace(keyToUpdate, valueToUpdate, existingKV.get(keyToUpdate));
        });

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        omeroKeyValuePairs.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // set annotation map
        MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
        newOmeroAnnotationMap.setContent(newNV);
        newOmeroAnnotationMap.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");

        // add key value pairs On OMERO
        boolean wasAdded = OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());

        // delete current keyValues
        boolean wasDeleted = OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());

        return (wasDeleted && wasAdded);
    }


    /**
     * Add metadata to the current image in the QuPath project
     *
     * @param keyValues
     * @return
     */
    public static void addMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split key value pairs into those that already exist in QuPath and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);

        // add metadata
        newKV.forEach(entry::putMetadataValue);
    }


    /**
     * Add OMERO key value pairs as metadata to the current image in the QuPath project
     *
     * @param imageServer
     */
    public static void addOmeroKeyValues(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return;

        // add metadata
        addMetadata(omeroKeyValuePairs);
    }


    /**
     * Add metadata to the current image in QuPath. Update existing metadata with new values and add new ones.
     *
     * @param keyValues
     * @return
     */
    public static void addAndUpdateMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split key value pairs metadata into those that already exist in QuPath and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);
        Map<String,String> updatedKV = new HashMap<>();

        // update metadata
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

        // add metadata
        updatedKV.forEach(entry::putMetadataValue);
        newKV.forEach(entry::putMetadataValue);
    }

    /**
     * Add OMERO key value pairs to the current image in QuPath. Update existing metadata with new values and add new ones.
     *
     * @param imageServer
     */
    public static void addOmeroKeyValuesAndUpdateMetadata(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return;

        // add and update metadata
        addAndUpdateMetadata(omeroKeyValuePairs);
    }

    /**
     * Delete all current metadata and add OMERO key value pairs to the current image in QuPath.
     *
     * @param imageServer
     * @return
     */
    public static void addOmeroKeyValuesAndDeleteMetadata(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValues = importOmeroKeyValues(imageServer);

        if(omeroKeyValues == null || omeroKeyValues.isEmpty())
            return;

        // add and delete metadata
        addAndDeleteMetadata(omeroKeyValues);
    }

    /**
     * Delete all current metadata and add key value pairs to the current image in QuPath.
     *
     * @param keyValues
     * @return
     */
    public static void addAndDeleteMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(QPEx.getQuPath().getImageData());

        // delete metadata
        entry.clearMetadata();

        // add new metadata
        keyValues.forEach(entry::putMetadataValue);
    }

    /**
     * read key value pairs from OMERO and check if all keys are unique
     *
     * @param imageServer
     * @return
     */
    public static Map<String,String> importOmeroKeyValues(OmeroRawImageServer imageServer) {
        // read current key-value on OMERO
        List<NamedValue> currentOmeroKeyValues = OmeroRawTools.readKeyValuesAsNamedValue(imageServer.getClient(), imageServer.getId());

        if (currentOmeroKeyValues.isEmpty()) {
            Dialogs.showWarningNotification("Read key values on OMERO", "The current image does not have any KeyValues on OMERO");
            return new HashMap<>();
        }

        // check unique keys
        boolean uniqueOmeroKeys = OmeroRawTools.checkUniqueKeyInAnnotationMap(currentOmeroKeyValues);

        if (!uniqueOmeroKeys) {
            Dialogs.showErrorMessage("Keys not unique", "There are at least two identical keys on OMERO. Please make each key unique");
            return null;
        }

        return currentOmeroKeyValues.stream().collect(Collectors.toMap(e->e.name, e->e.value));
    }


    /**
     * get all tags linked to the current image on OMERO
     *
     * @param imageServer
     * @return list of read tags
     */
    public static List<String> importOmeroTags(OmeroRawImageServer imageServer) {
        // read tags
        List<TagAnnotationData> omeroTagAnnotations = OmeroRawTools.readTags(imageServer.getClient(), imageServer.getId());

        // collect and convert to list
        return omeroTagAnnotations.stream().map(TagAnnotationData::getTagValue).collect(Collectors.toList());
    }

    /**
     * send a list of tags to OMERO
     *
     * @param tags
     * @param imageServer
     * @return if tags has been added
     */
    public static boolean sendTagsToOmero(List<String> tags, OmeroRawImageServer imageServer){
        // get current OMERO tags
        List<String> currentTags = importOmeroTags(imageServer);

        // remove all existing tags
        tags.removeAll(currentTags);

        if(tags.isEmpty()) {
            Dialogs.showInfoNotification("Sending tags", "All tags are already existing on OMERO.");
            return false;
        }

        boolean wasAdded = true;
        for(String tag:tags) {
            // create new omero-compatible tag
            TagAnnotationData newOmeroTagAnnotation = new TagAnnotationData(tag);

            // send tag to OMERO
            wasAdded = wasAdded && OmeroRawTools.addTagsOnOmero(newOmeroTagAnnotation, imageServer.getClient(), imageServer.getId());
        }

        return wasAdded;
    }

    /**
     * send a tag to OMERO
     *
     * @param tag
     * @param imageServer
     * @return if tags has been added
     */
    public static boolean sendTagToOmero(String tag, OmeroRawImageServer imageServer){
        // get current OMERO tags
        List<String> currentTags = importOmeroTags(imageServer);

        // check if the tag exists
        if(currentTags.contains(tag)) {
            Dialogs.showInfoNotification("Sending tags", "The tag "+tag+"  already exists.");
            return false;
        }

        // create new omero-compatible tag
        TagAnnotationData newOmeroTagAnnotation = new TagAnnotationData(tag);

        // send tag to OMERO
        return OmeroRawTools.addTagsOnOmero(newOmeroTagAnnotation, imageServer.getClient(), imageServer.getId());
    }

    private static boolean sendMeasurementTableToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String tableName){
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        TableData table = OmeroRawTools.convertMeasurementTableToOmeroTable(pathObjects, ob);

        return OmeroRawTools.addTableToOmero(table, tableName, imageServer.getClient(), imageServer.getId());
    }

    public static boolean sendAnnotationMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTable(QP.getAnnotationObjects(), imageServer, imageData);
    }

    public static boolean sendAnnotationMeasurementTable(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        String qpprojName = QPEx.getQuPath().getProject().getName().split("/")[0];
        String name = "QP annotation table_"+qpprojName+"_"+new Date().toString().replace(":", "-");

        return sendMeasurementTableToOmero(annotationObjects, imageServer, imageData, name);
    }

    public static boolean sendDetectionMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTable(QP.getDetectionObjects(), imageServer, imageData);
    }

    public static boolean sendDetectionMeasurementTable(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        String qpprojName =  QPEx.getQuPath().getProject().getName().split("/")[0];
        String name = "QP detection table_"+qpprojName+"_"+new Date().toString().replace(":", "-");

        return sendMeasurementTableToOmero(detectionObjects, imageServer, imageData, name);
    }








    public static boolean sendAnnotationMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTableAsCSV(QP.getAnnotationObjects(), imageServer, imageData);
    }

    public static boolean sendAnnotationMeasurementTableAsCSV(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        String qpprojName = QPEx.getQuPath().getProject().getName().split("/")[0];
        String name = "QP annotation table_" + qpprojName + "_" + new Date().toString().replace(":", "-"); // replace ":" to be Windows compatible

        return sendMeasurementTableAsCSVToOmero(annotationObjects, imageServer, imageData, name);
    }

    public static boolean sendDetectionMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTableAsCSV(QP.getDetectionObjects(), imageServer, imageData);
    }

    public static boolean sendDetectionMeasurementTableAsCSV(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        String qpprojName = QPEx.getQuPath().getProject().getName().split("/")[0];
        String name = "QP detection table_" + qpprojName + "_" + new Date().toString().replace(":", "-"); // replace ":" to be Windows compatible

        return sendMeasurementTableAsCSVToOmero(detectionObjects, imageServer, imageData, name);
    }

    private static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String filename){
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        String path = QPEx.getQuPath().getProject().getPath().getParent().toString();
        File file = OmeroRawTools.buildCSVFileFromMeasurementTable(pathObjects, ob, filename, path);

        if (file.exists()) {
            boolean wasAdded = OmeroRawTools.addAttachmentToOmero(file, imageServer.getClient(), imageServer.getId());
            file.delete();
            return wasAdded;
        }
        else return false;
    }

}
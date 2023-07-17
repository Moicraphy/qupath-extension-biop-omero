package qupath.ext.biop.servers.omero.raw;

import fr.igred.omero.Client;
import omero.gateway.exception.DSOutOfServiceException;
import javafx.collections.ObservableList;

import omero.gateway.model.ChannelData;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.ROIData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.model.ChannelBinding;
import omero.model.NamedValue;
import omero.model.RenderingDef;
import omero.rtypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OmeroRawScripting {

    private static final String detectionFileBaseName = "QP detection table";
    private static final String annotationFileBaseName = "QP annotation table";
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawScripting.class);


    /**
     * This method creates an instance of simple-omero-client object to get access to the full simple-omero-client API,
     * developed by Pierre Pouchin (https://github.com/GReD-Clermont/simple-omero-client).
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     *
     * @return  fr.igred.omero.Client object
     */
    public static Client getSimpleOmeroClientInstance(OmeroRawImageServer imageServer) throws DSOutOfServiceException {
        // get the current OmeroRawClient
        OmeroRawClient omerorawclient = imageServer.getClient();

        // build the simple-omero-client using the ID of the current session
        Client simpleClient = new Client();
        simpleClient.connect(omerorawclient.getServerURI().getHost(), omerorawclient.getServerURI().getPort(), omerorawclient.getGateway().getSessionId(omerorawclient.getGateway().getLoggedInUser()));

        return simpleClient;
    }


    /**
     * Import ROIs from OMERO to QuPath and remove all current annotations/detections in QuPath.
     * See {@link #importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects)}
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     * @return The list of OMERO rois converted into pathObjects.
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer) {
        return importOmeroROIsToQuPath(imageServer, true);
    }


    /**
     * Import ROIs from OMERO to QuPath
     * <p>
     * <ul>
     * <li> Read ROIs from OMERO </li>
     * <li> Check if current annotations/detection have to be deleted or not </li>
     * <li> Add new pathObjects to the current image </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param removePathObjects Boolean to delete or keep pathObjects (annotations, detections) on the current image.
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects) {
        // read OMERO ROIs
        Collection<PathObject> pathObjects = imageServer.readPathObjects();

        // get the current hierarchy
        PathObjectHierarchy hierarchy = QP.getCurrentHierarchy();

        // remove current annotations
        if (removePathObjects)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(), false);

        // add pathObjects to the current hierarchy
        if (!pathObjects.isEmpty()) {
            hierarchy.addObjects(pathObjects);
            hierarchy.resolveHierarchy();
        }

        return pathObjects;
    }


    /**
     * Send all QuPath objects (annotations and detections) to OMERO and deletes ROIs already stored on OMERO
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer) {
        return sendPathObjectsToOmero(imageServer, true);
    }


    /**
     * Send all QuPath objects (annotations and detections) to OMERO.
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        Collection<PathObject> pathObjects = QP.getAnnotationObjects();
        pathObjects.addAll(QP.getDetectionObjects());
        return sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO);
    }


    /**
     * Send all QuPath annotation objects to OMERO, without deleting current ROIs on OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer) {
        return sendAnnotationsToOmero(imageServer, false);
    }


    /**
     * Send all QuPath annotation objects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIsOnOMERO);
    }


    /**
     * Send all QuPath detection objects to OMERO without deleting existing ROIs.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer) {
        return sendDetectionsToOmero(imageServer, false);
    }


    /**
     * Send all QuPath detections to OMERO and delete existing ROIs is specified.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        Collection<PathObject> detections = QP.getDetectionObjects();
        return sendPathObjectsToOmero(imageServer, detections, deleteROIsOnOMERO);
    }


    /**
     * Send a collection of QuPath objects (annotations and/or detections) to OMERO, without deleting existing ROIs
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects) {
        return sendPathObjectsToOmero(imageServer, pathObjects, false);
    }


    /**
     * Send a collection of pathObjects to OMERO.
     *
     * <p>
     * <ul>
     * <li> Convert pathObjects to OMERO ROIs </li>
     * <li> Delete all current ROIs on OMERO if explicitly asked </li>
     * <li> Send ROIs to the current image on OMERO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects, boolean deleteROIsOnOMERO) {
        // convert pathObjects to OMERO ROIs
        List<ROIData> omeroROIs = OmeroRawTools.createOmeroROIsFromPathObjects(pathObjects);

        // get omero client and image id to send ROIs to the correct image
        OmeroRawClient client = imageServer.getClient();
        long imageId = imageServer.getId();

        // delete ROIs
        if (deleteROIsOnOMERO) {
            // get existing ROIs
            List<ROIData> existingROIs = OmeroRawTools.readOmeroROIs(client, imageId);
            // write new ROIs
            boolean hasBeenWritten = OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);
            // delete previous ROIs
            OmeroRawTools.deleteOmeroROIs(client, existingROIs);

            return hasBeenWritten;
        } else {
            return OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);
        }
    }


    /**
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
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
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
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
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent.
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
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
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
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
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
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
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
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
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
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
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
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
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
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
     * Read, from OMERO, Key-Value pairs attached to the current image and check if all keys are unique. If they are not unique, no Key-Values are returned.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return map of OMERO Key-Value pairs
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
     * Read, from OMERO, tags attached to the current image.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return list of OMERO tags.
     */
    public static List<String> importOmeroTags(OmeroRawImageServer imageServer) {
        // read tags
        List<TagAnnotationData> omeroTagAnnotations = OmeroRawTools.readTags(imageServer.getClient(), imageServer.getId());

        // collect and convert to list
        return omeroTagAnnotations.stream().map(TagAnnotationData::getTagValue).collect(Collectors.toList());
    }


    /**
     * Send a list of tags to OMERO. If tags are already attached to the image, these tags are not sent.
     *
     * @param tags List of tags to add to the image
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if tags have been sent ; false if there were troubles during the sending process)
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
     * Send a tag to OMERO. If the tag is already attached to the image, it is not sent.
     *
     * @param tag The tag to add to the image
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if tag has been sent ; false if there were troubles during the sending process)
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


    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table
     *
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param tableName Name of the OMERO.table
     * @param deletePreviousTable Delete of not all previous OMERO measurement tables
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    private static boolean sendMeasurementTableToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String tableName, boolean deletePreviousTable){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        OmeroRawClient client = imageServer.getClient();
        Long imageId = imageServer.getId();

        // convert the table to OMERO.table
        TableData table = OmeroRawTools.convertMeasurementTableToOmeroTable(pathObjects, ob, client, imageId);

        if(deletePreviousTable){
            Collection<FileAnnotationData> tables = OmeroRawTools.readTables(client, imageId);
            boolean hasBeenSent = OmeroRawTools.addTableToOmero(table, tableName, client, imageId);
            deletePreviousFileVersions(imageServer, tables, tableName.substring(0, tableName.lastIndexOf("_")));

            return hasBeenSent;
        } else
            // send the table to OMERO
            return OmeroRawTools.addTableToOmero(table, tableName, client, imageId);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param tableName Name of the table to upload
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMeasurementTableToOmero(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String tableName){
        return sendMeasurementTableToOmero(annotationObjects, imageServer, imageData, tableName, false);
    }

    /**
     * Send all annotations measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTable(QP.getAnnotationObjects(), imageServer, imageData);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTable(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the table name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableToOmero(annotationObjects, imageServer, imageData, name);
    }

    /**
     * Send all detections measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTable(QP.getDetectionObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table with a default table name referring to detections
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTable(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the table name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableToOmero(detectionObjects, imageServer, imageData, name);
    }


    /**
     * Send all annotations measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTableAsCSV(QP.getAnnotationObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param annotationObjects QuPath annotation objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTableAsCSV(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the file name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableAsCSVToOmero(annotationObjects, imageServer, imageData, name);
    }


    /**
     * Send all detections measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTableAsCSV(QP.getDetectionObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTableAsCSV(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the file name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableAsCSVToOmero(detectionObjects, imageServer, imageData, name);
    }

    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param pathObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the file to upload
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String filename){
        return sendMeasurementTableAsCSVToOmero(pathObjects, imageServer, imageData, filename, false);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table
     *
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the CSV file
     * @param deletePreviousTable Delete or not all previous versions of csv measurements tables
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    private static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String filename, boolean deletePreviousTable){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        // get the path
        String path = QPEx.getQuPath().getProject().getPath().getParent().toString();

        // build the csv file from the measurement table
        File file = OmeroRawTools.buildCSVFileFromMeasurementTable(pathObjects, ob, imageServer.getId(), filename, path);

        boolean hasBeenSent = false;
        if (file.exists()) {
            OmeroRawClient client = imageServer.getClient();
            long imageId = imageServer.getId();

            if (deletePreviousTable) {
                Collection<FileAnnotationData> attachments = OmeroRawTools.readAttachments(client, imageId);
                hasBeenSent = OmeroRawTools.addAttachmentToOmero(file, client, imageId);
                deletePreviousFileVersions(imageServer, attachments, filename.substring(0,filename.lastIndexOf("_")));

            } else
                // add the csv file to OMERO
                hasBeenSent = OmeroRawTools.addAttachmentToOmero(file, client, imageId);

            // delete the temporary file
            file.delete();
        }
        return hasBeenSent;
    }


    /**
     * Return the files as FileAnnotationData attached to the current image from OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return
     */
    public static List<FileAnnotationData> readFilesAttachedToCurrentImageOnOmero(OmeroRawImageServer imageServer){
        return OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer, files, name);
    }


    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     */
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer, files, name);
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer, files, name);
    }

    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     */
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer, files, name);
    }

    /**
     * Delete all previous version of a file, identified by the name given in parameters. This name may or may not be the
     * full name of the files to delete.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param name contained in the table/file name to delete (i.e. filtering item). It may be a part of the full table/file name
     */
    public static void deleteFilesOnOmero(OmeroRawImageServer imageServer, String name){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        deletePreviousFileVersions(imageServer, files, name);
    }

    /**
     * Delete all previous version of tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the given table name in the list of files.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @param name Table name that files name must contain to be deleted (i.e. filtering item)
     */
    private static void deletePreviousFileVersions(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files, String name){
        if(!files.isEmpty()) {
            List<FileAnnotationData> previousTables = files.stream()
                    .filter(e -> e.getFileName().contains(name))
                    .collect(Collectors.toList());

            if (!previousTables.isEmpty())
                OmeroRawTools.deleteFiles(imageServer.getClient(), previousTables);
            else logger.warn("Sending tables : No previous table attached to the image");
        }
    }


    /**
     * Set the minimum and maximum display range value of each channel on QuPath, based on OMERO settings.<br>
     * QuPath image and thumbnail are updated accordingly.<br>
     * Channel indices are taken as reference.
     *
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsDisplayRangeFromOmeroChannel(OmeroRawImageServer imageServer) {
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        ImageData<BufferedImage> imageData = QPEx.getQuPath().getImageData();
        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get the min-max per channel from OMERO
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            double minDynamicRange = binding.getInputStart().getValue();
            double maxDynamicRange = binding.getInputEnd().getValue();

            // set the dynamic range
            QPEx.setChannelDisplayRange(imageData, c, minDynamicRange, maxDynamicRange);
        }

        // Update the thumbnail
        updateQuPathThumbnail();
    }


    /**
     * Set the color of each channel on QuPath, based on OMERO settings.<br>
     * QuPath image and thumbnail are updated accordingly.<br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsColorFromOmeroChannel(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        List<Integer> colors = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            // get OMERO channels color
            colors.add(new Color(binding.getRed().getValue(),binding.getGreen().getValue(), binding.getBlue().getValue(), binding.getAlpha().getValue()).getRGB());
        }

        // set QuPath channels color
        QPEx.setChannelColors(QPEx.getQuPath().getImageData(), colors.toArray(new Integer[0]));

        // Update the thumbnail
        updateQuPathThumbnail();

    }

    /**
     * Update QuPath thumbnail
     */
    private static void updateQuPathThumbnail(){
        try {
            // saved changes
            QPEx.getQuPath().getProject().syncChanges();

            // get the current image data
            ImageData<BufferedImage> newImageData = QPEx.getQuPath().getViewer().getImageDisplay().getImageData();

            // generate thumbnail
            BufferedImage thumbnail = QPEx.getQuPath().getViewer().getRGBThumbnail();

            // get and save the new thumbnail
            ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(newImageData);
            entry.setThumbnail(thumbnail);
            entry.saveImageData(newImageData);

            // save changes
            QPEx.getQuPath().getProject().syncChanges();
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Set the name of each channel on QuPath, based on OMERO settings.
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsNameFromOmeroChannel(OmeroRawImageServer imageServer){
        // get the number of the channels in OMERO
        List<ChannelData> omeroChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId());

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroChannels.size() != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroChannels.size()+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        List<String> names = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get OMERO channels name
            names.add(omeroChannels.get(c).getName());
        }

        // set QuPath channels name
        QPEx.setChannelNames(QPEx.getQuPath().getImageData(), names.toArray(new String[0]));
    }



    /**
     * Set the minimum and maximum display range value of each channel on OMERO, based on QuPath settings.<br>
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsDisplayRangeToOmero(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("OMERO channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return false;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroNChannels+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            double minDisplayRange = qpChannels.get(c).getMinDisplay();
            double maxDisplayRange = qpChannels.get(c).getMaxDisplay();

            // set the rendering settings with new min/max values
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            binding.setInputStart(rtypes.rdouble(minDisplayRange));
            binding.setInputEnd(rtypes.rdouble(maxDisplayRange));
        }

        // update the image on OMERO first
        boolean updateImageDisplay = OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), renderingSettings);

        // update the image thumbnail on OMERO
        boolean updateThumbnail = OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(),imageServer.getId(),renderingSettings.getId().getValue());

        return updateImageDisplay && updateThumbnail;
    }


    /**
     * Set the name of each channel on OMERO, based on QuPath settings.
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsNameToOmero(OmeroRawImageServer imageServer){
        // get the number of the channels in OMERO
        List<ChannelData> omeroChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId());

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroChannels.size() != qpChannels.size()){ // can use imageServer.nChannels() to get the real number of channel
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroChannels.size()+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            String qpChName = imageServer.getChannel(c).getName();

            // set the rendering settings with new min/max values
            omeroChannels.get(c).setName(qpChName);
        }

        // update the image on OMERO first
        return OmeroRawTools.updateObjectsOnOmero(imageServer.getClient(), omeroChannels.stream().map(ChannelData::asIObject).collect(Collectors.toList()));
    }


    /**
     * Set the color of each channel on OMERO, based on QuPath settings.
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsColorToOmero(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("OMERO channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return false;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){ // can use imageServer.nChannels() to get the real number of channel
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroNChannels+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            Integer colorInt = qpChannels.get(c).getColor();
            Color color = new Color(colorInt);

            // set the rendering settings with new min/max values
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            binding.setBlue(rtypes.rint(color.getBlue()));
            binding.setRed(rtypes.rint(color.getRed()));
            binding.setGreen(rtypes.rint(color.getGreen()));
            binding.setAlpha(rtypes.rint(color.getAlpha()));
        }

        // update the image on OMERO first
        boolean updateImageDisplay = OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), renderingSettings);

        // update the image thumbnail on OMERO
        boolean updateThumbnail = OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(), imageServer.getId(), renderingSettings.getId().getValue());

        return updateImageDisplay && updateThumbnail;
    }

    /**
     * Set the name the image on OMERO, based on QuPath settings.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image name on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendImageNameToOmero(OmeroRawImageServer imageServer){
        // get the image
        omero.gateway.model.ImageData image = OmeroRawTools.readOmeroImage(imageServer.getClient(), imageServer.getId());
        if(image != null) {
            image.setName(QPEx.getCurrentImageName());

            // update the image on OMERO first
            return OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), image.asIObject());
        }

        return false;
    }


}
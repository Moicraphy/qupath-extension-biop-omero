package qupath.ext.biop.servers.omero.raw;

import omero.RLong;
import omero.gateway.model.DataObject;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ScreenData;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.ProjectDatasetLink;
import omero.model.DatasetImageLink;
import omero.model.IObject;
import omero.model.ScreenPlateLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class OmeroRawBrowserTools {


    final private static Logger logger = LoggerFactory.getLogger(OmeroRawBrowserTools.class);

    /**
     * Get all the OMERO objects (inside the parent Id) present in the OMERO server with the specified
     * URI.
     * <p>
     * No orphaned {@code OmeroRawObject} will be fetched.
     *
     * @param client
     * @param parent
     * @return list of OmeroRawObjects
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOmeroObjectsItems(OmeroRawObjects.OmeroRawObject parent, OmeroRawClient client,
                                                                             OmeroRawObjects.Group group, OmeroRawObjects.Owner owner) {
        if (parent == null)
            return new ArrayList<>();

        // get the child type
        OmeroRawObjects.OmeroRawObjectType type = OmeroRawObjects.OmeroRawObjectType.PROJECT;
        if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT)
            type = OmeroRawObjects.OmeroRawObjectType.DATASET;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET)
            type = OmeroRawObjects.OmeroRawObjectType.IMAGE;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.SCREEN)
            type = OmeroRawObjects.OmeroRawObjectType.PLATE;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE)
            type = OmeroRawObjects.OmeroRawObjectType.PLATE_ACQUISITION;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE_ACQUISITION)
            type = OmeroRawObjects.OmeroRawObjectType.WELL;
        else if (parent.getType() == OmeroRawObjects.OmeroRawObjectType.WELL)
            type = OmeroRawObjects.OmeroRawObjectType.IMAGE;

        // get OMERO owner and group
        Experimenter user = OmeroRawTools.getOmeroUser(client, owner.getId(), owner.getName());
        ExperimenterGroup userGroup = OmeroRawTools.getOmeroGroup(client, group.getId(), group.getName());
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        if (type == OmeroRawObjects.OmeroRawObjectType.PROJECT) {
            // get all projects for the current user
            Collection<ProjectData> projects = new ArrayList<>(OmeroRawTools.readOmeroProjectsByUser(client, owner.getId()));

            // create OmeroRawObjects from child project
            for(ProjectData project : projects)
                list.add(new OmeroRawObjects.Project("",project, project.getId(), type, parent, user, userGroup));

            // get all screens for the current user
            Collection<ScreenData> screens = new ArrayList<>(OmeroRawTools.readOmeroScreensByUser(client, owner.getId()));

            // create OmeroRawObjects from child screen
            for(ScreenData screen : screens)
                list.add(new OmeroRawObjects.Screen("",screen, screen.getId(), OmeroRawObjects.OmeroRawObjectType.SCREEN, parent, user, userGroup));
            }
            else if (type == OmeroRawObjects.OmeroRawObjectType.DATASET) {
                // get the current project to have access to the child datasets
                ProjectData projectData = (ProjectData)parent.getData();

                // if the current project has some datasets
                if(projectData.asProject().sizeOfDatasetLinks() > 0){
                    // get child datasets ids
                    List<ProjectDatasetLink> links = projectData.asProject().copyDatasetLinks();
                    List<Long> linksId = links.stream()
                            .map(ProjectDatasetLink::getChild)
                            .map(IObject::getId)
                            .map(RLong::getValue)
                            .collect(Collectors.toList());

                    // get child datasets
                    List<DatasetData> datasets = new ArrayList<>(OmeroRawTools.readOmeroDatasets(client,linksId));

                    // create OmeroRawObjects from child datasets
                    for(DatasetData dataset : datasets)
                        list.add(new OmeroRawObjects.Dataset("",dataset ,dataset.getId(), type, parent, user, userGroup));
                }
            }
            else if (type == OmeroRawObjects.OmeroRawObjectType.PLATE) {
                // get the current project to have access to the child datasets
                ScreenData screenData = (ScreenData)parent.getData();

                // if the current project has some datasets
                if(parent.getNChildren() > 0){
                    // get child datasets ids
                    Set<PlateData> plates = screenData.getPlates();
                    List<Long> plateIds = plates.stream().map(PlateData::getId).collect(Collectors.toList());

                    // get child datasets
                    //List<PlateData> plateDataList = new ArrayList<>(OmeroRawTools.readOmeroPlates(client,plateIds));

                    // create OmeroRawObjects from child datasets
                    for(PlateData plate : plates)
                        list.add(new OmeroRawObjects.Plate("",plate ,plate.getId(), type, parent, user, userGroup));
                }
            }
            else if (type == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                // get the current dataset to have access to the child images
                DatasetData datasetColl = OmeroRawTools.readOmeroDatasets(client, Collections.singletonList(parent.getId())).iterator().next();

                if(datasetColl.asDataset().sizeOfImageLinks() > 0){
                    List<ImageData> images = new ArrayList<>();
                    List<DatasetImageLink> links = datasetColl.asDataset().copyImageLinks();

                    // get child images
                    for (DatasetImageLink link : links) {
                        images.add(new ImageData(link.getChild()));
                    }

                    // create OmeroRawObjects from child images
                    for(ImageData image : images)
                        list.add(new OmeroRawObjects.Image("",image ,image.getId(), type, parent, user, userGroup));
                }
            }

        return list;
    }


    /**
     * build an {@link OmeroRawObjects.Owner} object based on the OMERO {@link Experimenter} user
     *
     * @param user
     * @return
     */
    public static OmeroRawObjects.Owner getOwnerItem(Experimenter user){
        return new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                user.getLastName()==null ? "" : user.getLastName().getValue(),
                user.getEmail()==null ? "" : user.getEmail().getValue(),
                user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                user.getOmeName()==null ? "" : user.getOmeName().getValue());
    }

    /**
     * return the {@link OmeroRawObjects.Owner} object corresponding to the logged-in user on the current OMERO session
     *
     * @param client
     * @return
     */
    public static OmeroRawObjects.Owner getDefaultOwnerItem(OmeroRawClient client)  {
        return getOwnerItem(client.getLoggedInUser());
    }


    /**
     * return the group object corresponding to the default group attributed to the logged in user
     * @param client
     * @return
     */
    public static OmeroRawObjects.Group getDefaultGroupItem(OmeroRawClient client) {
        ExperimenterGroup userGroup = OmeroRawTools.getDefaultOmeroGroup(client, client.getLoggedInUser().getId().getValue());
        return new OmeroRawObjects.Group(userGroup.getId().getValue(), userGroup.getName().getValue());
    }

    /**
     * return a map of available groups with its attached users.
     *
     * @param client
     * @return
     */
    public static Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> getGroupUsersMapAvailableForCurrentUser(OmeroRawClient client) {
        // final map
        Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> map = new HashMap<>();

        // get all available groups for the current user according to his admin rights
        List<ExperimenterGroup> groups;
        if(client.getIsAdmin())
            groups = OmeroRawTools.getAllOmeroGroups(client);
        else
            groups = OmeroRawTools.getUserOmeroGroups(client,client.getLoggedInUser().getId().getValue());

        // remove "system" and "user" groups
        groups.stream()
                .filter(group->group.getId().getValue() != 0 && group.getId().getValue() != 1)
                .collect(Collectors.toList())
                .forEach(group-> {
                    // initialize lists
                    List<OmeroRawObjects.Owner> owners = new ArrayList<>();
                    OmeroRawObjects.Group userGroup = new OmeroRawObjects.Group(group.getId().getValue(), group.getName().getValue());

                    // get all available users for the current group
                    List<Experimenter> users = OmeroRawTools.getOmeroUsersInGroup(client, group.getId().getValue());

                    // convert each user to qupath compatible owners object
                    for (Experimenter user : users)
                        owners.add(getOwnerItem(user));

                    // sort in alphabetic order
                    owners.sort(Comparator.comparing(OmeroRawObjects.Owner::getName));
                    map.put(userGroup, owners);
                });

        return new TreeMap<>(map);
    }


    /**
     * get all the orphaned dataset from the server for a certain user as list of {@link OmeroRawObjects.OmeroRawObject}
     *
     * @param client the client {@code OmeroRawClient} object
     * @return list of orphaned datasets
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOrphanedDatasetsItem(OmeroRawClient client, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner) {
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        // get orphaned datasets
        Collection<DatasetData> orphanedDatasets = OmeroRawTools.readOmeroOrphanedDatasetsPerOwner(client, owner.getId());

        // get OMERO user and group
        Experimenter user = OmeroRawTools.getOmeroUser(client, owner.getId(), owner.getName());
        ExperimenterGroup userGroup = OmeroRawTools.getOmeroGroup(client, group.getId(), group.getName());

        // convert dataset to OmeroRawObject
        orphanedDatasets.forEach( e -> {
            OmeroRawObjects.OmeroRawObject omeroObj = new OmeroRawObjects.Dataset("", e, e.getId(), OmeroRawObjects.OmeroRawObjectType.DATASET, new OmeroRawObjects.Server(client.getServerURI()), user, userGroup);
            list.add(omeroObj);
        });

        return list;
    }


    /**
     * get all the orphaned images from the server for a certain user as list of {@link OmeroRawObjects.OmeroRawObject}
     *
     * @param client
     * @param group
     * @param owner
     * @return
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOrphanedImagesItem(OmeroRawClient client, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner){
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        // get orphaned datasets
        Collection<ImageData> orphanedImages = OmeroRawTools.readOmeroOrphanedImagesPerUser(client, owner.getId());

        // get OMERO user and group
        Experimenter user = OmeroRawTools.getOmeroUser(client, owner.getId(), owner.getName());
        ExperimenterGroup userGroup = OmeroRawTools.getOmeroGroup(client, group.getId(), group.getName());

        // convert dataset to OmeroRawObject
        orphanedImages.forEach( e -> {
            OmeroRawObjects.OmeroRawObject omeroObj = new OmeroRawObjects.Image("", e, e.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, new OmeroRawObjects.Server(client.getServerURI()),user, userGroup);
            list.add(omeroObj);
        });

        return list;
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
    public static OmeroRawAnnotations readAnnotationsItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject obj, OmeroRawAnnotations.OmeroRawAnnotationType category) {
        return OmeroRawAnnotations.getOmeroAnnotations(client, category, OmeroRawTools.readOmeroAnnotations(client, obj.getId()));
    }

    public static void addContainersAsMetadataFields(OmeroRawClient client, ProjectImageEntry<BufferedImage> entry){
        try {
            long imageId = ((OmeroRawImageServer) (entry.readImageData().getServer())).getId();
            Collection<? extends DataObject> datasets = OmeroRawTools.getParent(client, "Image", imageId);

            if (!datasets.isEmpty()) {
                entry.putMetadataValue("Dataset", ((DatasetData) (datasets.iterator().next())).getName());
                long datasetId = datasets.iterator().next().getId();
                Collection<? extends DataObject> projects = OmeroRawTools.getParent(client, "Dataset", datasetId);

                if (!projects.isEmpty()) {
                    entry.putMetadataValue("Project", ((ProjectData) (projects.iterator().next())).getName());
                } else entry.putMetadataValue("Project", "None");
            } else {
                entry.putMetadataValue("Dataset", "None");
                entry.putMetadataValue("Project", "None");
            }
        }catch(IOException error){
            logger.error("Cannot add default OMERO container as metadata to QuPath for image "+ entry.getImageName());
        }
    }

}

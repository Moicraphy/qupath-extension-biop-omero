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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import omero.ServerError;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.AdminFacility;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.*;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.GroupExperimenterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.annotations.SerializedName;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ObservableList;
//import fr.igred.omero.repository;


/**
 * Class regrouping all OMERO objects (most of which will be instantiated through deserialization) that represent
 * OMERO objects or data.
 *
 * @author Melvin Gelbard
 */
final class OmeroRawObjects {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawObjects.class);

    public static enum OmeroRawObjectType {
        SERVER("#Server", "Server"),
        PROJECT("http://www.openmicroscopy.org/Schemas/OME/2016-06#Project", "Project"),
        DATASET("http://www.openmicroscopy.org/Schemas/OME/2016-06#Dataset", "Dataset"),
        IMAGE("http://www.openmicroscopy.org/Schemas/OME/2016-06#Image", "Image"),
        PLATE("TODO", "Plate"),
        WELL("TODO", "Well"),
        SCREEN("TODO", "Screen"),

        // Object for OmeroWebBrowser's 'Orphaned folder' item (not for deserialization)
        ORPHANED_FOLDER("#OrphanedFolder", "Orphaned Folder"),

        // Default if unknown
        UNKNOWN("", "Unknown");

        private final String APIName;
        private final String displayedName;
        private OmeroRawObjectType(String APIName, String displayedName) {
            this.APIName = APIName;
            this.displayedName = displayedName;
        }

        static OmeroRawObjectType fromString(String text) {
            for (var type : OmeroRawObjectType.values()) {
                if (type.APIName.equalsIgnoreCase(text) || type.displayedName.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        String toURLString() {
            return displayedName.toLowerCase() + 's';
        }

        @Override
        public String toString() {
            return displayedName;
        }
    }


    /*static class GsonOmeroObjectDeserializer implements JsonDeserializer<OmeroObject> {

        @Override
        public OmeroObject deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {

            var type = OmeroObjectType.fromString(((JsonObject)json).get("@type").getAsString().toLowerCase());

            OmeroObject omeroObj;
            if (type.equals(OmeroObjectType.PROJECT))
                omeroObj = context.deserialize(json, Project.class);
            else if (type.equals(OmeroObjectType.DATASET))
                omeroObj = context.deserialize(json, Dataset.class);
            else if (type.equals(OmeroObjectType.IMAGE))
                omeroObj = context.deserialize(json, Image.class);
            else {
                logger.warn("Unsupported type {}", type);
                return null;
            }
            Owner owner = context.deserialize(((JsonObject)json).get("omero:details").getAsJsonObject().get("owner"), Owner.class);
            Group group = context.deserialize(((JsonObject)json).get("omero:details").getAsJsonObject().get("group"), Group.class);
            if (owner != null)
                omeroObj.setOwner(owner);
            if (group != null)
                omeroObj.setGroup(group);

            return omeroObj;
        }
    }*/


    static abstract class OmeroRawObject {

        private long id = -1;
        protected String name;
        protected String type;
        private Owner owner;
        private Group group;
        private OmeroRawObject parent;

        /**
         * Return the OMERO ID associated with this object.
         * @return id
         */
        long getId() {
            return id;
        }

        /**
         * Set the id of this object
         * @param id
         */
        void setId(long id) {
            this.id = id;
        }

        /**
         * Return the name associated with this object.
         * @return name
         */
        String getName() {
            return name;
        }

        /**
         * Set the name of this object
         * @param name
         */
        void setName(String name) {
            this.name = name;
        }


        /**
         * Return the URL associated with this object.
         * @return url
         */
        abstract String getAPIURLString();

        /**
         * Return the {@code OmeroObjectType} associated with this object.
         * @return type
         */
        OmeroRawObjectType getType() {
            return OmeroRawObjectType.fromString(type);
        }

        /**
         * Set the type of this object
         * @param type
         */
        void setType(String type) {
            this.type = OmeroRawObjectType.fromString(type).toString();
        }

        /**
         * Return the OMERO owner of this object
         * @return owner
         */
        Owner getOwner() {
            return owner;
        }

        /**
         * Set the owner of this OMERO object
         * @param owner
         */
        void setOwner(Owner owner) {
            this.owner = owner;
        }

        /**
         * Return the OMERO group of this object
         * @return group
         */
        Group getGroup() {
            return group;
        }

        /**
         * Set the group of this OMERO object
         * @param group
         */
        void setGroup(Group group) {
            this.group = group;
        }

        /**
         * Return the parent of this object
         * @return parent
         */
        OmeroRawObject getParent() {
            return parent;
        }

        /**
         * Set the parent of this OMERO object
         * @param parent
         */
        void setParent(OmeroRawObject parent) {
            this.parent = parent;
        }

        /**
         * Return the number of children associated with this object
         * @return nChildren
         */
        int getNChildren() {
            return 0;
        }

        /*abstract void init(long id, OmeroRawObjectType type, String name, OmeroRawClient client, DataObject data);{
            this.parent = null;
            this.group = client.getGateway().getAdminService(client.getContext()).getGroup(data.getGroupId()).getName().toString();
            this.id = id;
            this.name = name;
            this.owner = data.getOwner().getn
        }*/


        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (!(obj instanceof OmeroRawObject))
                return false;

            return id == ((OmeroRawObject)obj).getId();
        }
    }

    static class Server extends OmeroRawObject {

        private String url;

        public Server(URI uri) {
            super.id = -1;
            super.type = "Server";
            super.owner = null;
            super.name = "";
            super.group = null;
            super.parent = null;
            this.url = uri.toString();
        }

        @Override
        String getAPIURLString() {
            return url;
        }
    }


    /**
     * The {@code Orphaned folder} class differs from other in this class as it
     * is never created through deserialization of JSON objects. Note that it should only
     * contain orphaned images, <b>not</b> orphaned datasets (like the OMERO webclient).
     * <p>
     * It should only be used once per {@code OmeroWebImageServerBrowser}, with its children objects loaded
     * in an executor (see {@link OmeroRawTools#populateOrphanedImageList(URI, OrphanedFolder)}). This class keeps track of:
     * <li>Total child count: total amount of orphaned images on the server.</li>
     * <li>Current child count: what is displayed in the current {@code OmeroWebServerImageBrowser}, which depends on what is loaded and the current Group/Owner.</li>
     * <li>Child count: total amount of orphaned images currently loaded (always smaller than total child count).</li>
     * <li>{@code isLoading} property: defines whether QuPath is still loading its children objects.</li>
     * <li>List of orphaned image objects.</li>
     */
    static class OrphanedFolder extends OmeroRawObject {

        /**
         * Number of children currently to display (based on Group/Owner and loaded objects)
         */
        private IntegerProperty currentChildCount;

        /**
         * Number of children objects loaded
         */
        private AtomicInteger loadedChildCount;

        /**
         * Total number of children (loaded + unloaded)
         */
        private AtomicInteger totalChildCount;

        private BooleanProperty isLoading;
        private ObservableList<OmeroRawObject> orphanedImageList;

        public OrphanedFolder(ObservableList<OmeroRawObject> orphanedImageList) {
            this.name = "Orphaned Images";
            this.type = OmeroRawObjectType.ORPHANED_FOLDER.toString();
            this.currentChildCount = new SimpleIntegerProperty(0);
            this.loadedChildCount = new AtomicInteger(0);
            this.totalChildCount = new AtomicInteger(-1);
            this.isLoading = new SimpleBooleanProperty(true);
            this.orphanedImageList = orphanedImageList;
        }

        IntegerProperty getCurrentCountProperty() {
            return currentChildCount;
        }

        int incrementAndGetLoadedCount() {
            return loadedChildCount.incrementAndGet();
        }

        void setTotalChildCount(int newValue) {
            totalChildCount.set(newValue);
        }

        int getTotalChildCount() {
            return totalChildCount.get();
        }

        BooleanProperty getLoadingProperty() {
            return isLoading;
        }

        void setLoading(boolean value) {
            isLoading.set(value);
        }

        ObservableList<OmeroRawObject> getImageList() {
            return orphanedImageList;
        }

        @Override
        int getNChildren() {
            return currentChildCount.get();
        }

        @Override
        String getAPIURLString() {
            return "";
        }
    }

    static class Project extends OmeroRawObject {

        private String url;
        private String description;
        private int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }

        public Project(String url, ProjectData projectData, long id, OmeroRawObjectType type, OmeroRawClient client, OmeroRawObject parent) throws DSOutOfServiceException, ServerError {
            this.url = url;
            this.description = projectData.getDescription();
            this.childCount = projectData.asProject().sizeOfDatasetLinks();
            super.setId(id);
            super.setName(projectData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            //TODO change the way to extract user info because it is only the one which is logged
            //System.out.println(projectData.asExperimenter().getFirstName());
           /* List<ExperimenterGroup> groups = client.getGateway().getAdminService(client.getContext()).lookupGroups();
            List<omero.model.Experimenter> expermienters = client.getGateway().getAdminService(client.getContext()).lookupExperimenters();
           // System.out.println(client.getGateway().);
            //List<GroupExperimenterMap> links = client.getGateway().getAdminService(client.getContext()).getGroup(projectData.getGroupId()).copyGroupExperimenterMap();//.getExperimenter(1).copyGroupExperimenterMap();
            for (ExperimenterGroup group:groups) {
                System.out.println("group.getName().getValue()");
                System.out.println(group.getName().getValue());
                System.out.println("group.copyGroupExperimenterMap().get(0).getChild().getFirstName().getValue()");
                System.out.println(group.copyGroupExperimenterMap().get(0).getChild().getFirstName().getValue());
                client.getGateway().getAdminService(client.getContext()).getGroup(projectData.getGroupId());
                System.out.println("blblb");
                System.out.println(client.getGateway().getAdminService(client.getContext()).getExperimenter(projectData.getOwner().getId()));
                System.out.println(client.getGateway().getAdminService(client.getContext()).getExperimenter(projectData.getOwner().getId()).getFirstName().getValue());
            }

            //List<omero.model.Experimenter> expermienters = client.getGateway().getAdminService(client.getContext()).lookupGroup(groups.get(0).getName().toString()).linkedExperimenterList();
            System.out.println(expermienters.get(0));
            for (omero.model.Experimenter exp:expermienters) {
                System.out.println("exp.getFirstName().getValue()");
                System.out.println(exp.getFirstName().getValue());
            }*/
            omero.model.Experimenter user = client.getGateway().getAdminService(client.getContext()).getExperimenter(projectData.getOwner().getId());
           /* System.out.println("id : " + user.getId().getValue());
            System.out.println("first name : " + user.getFirstName().getValue());
            System.out.println("middle name : " + user.getMiddleName());
            System.out.println("last name : " + user.getLastName().getValue());
            System.out.println("email : " + user.getEmail().getValue());
            System.out.println("institution : " + user.getInstitution().getValue());
            System.out.println("omemanem : " + user.getOmeName().getValue());*/

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            //System.out.println("group "+ client.getGateway().getAdminService(client.getContext()).getGroup(projectData.getGroupId()));
            //System.out.println("groupName "+ client.getGateway().getAdminService(client.getContext()).getGroup(projectData.getGroupId()).getName().toString());
            super.setGroup(new Group(projectData.getGroupId(),
                    client.getGateway().getAdminService(client.getContext()).getGroup(projectData.getGroupId()).getName().getValue()));

        }
    }

    static class Dataset extends OmeroRawObject {

        private String url;
        private String description;
        private int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }

        public Dataset(String url, DatasetData datasetData, long id, OmeroRawObjectType type, OmeroRawClient client, OmeroRawObject parent) throws DSOutOfServiceException, ServerError {
            this.url = url;
            this.description = datasetData.getDescription();
            this.childCount = datasetData.asDataset().sizeOfImageLinks();
            super.setId(id);
            super.setName(datasetData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            omero.model.Experimenter user = client.getGateway().getAdminService(client.getContext()).getExperimenter(datasetData.getOwner().getId());
            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(datasetData.getGroupId(),
                    client.getGateway().getAdminService(client.getContext()).getGroup(datasetData.getGroupId()).getName().getValue()));

        }
    }

    static class Image extends OmeroRawObject {
        private String url;
        private long acquisitionDate = -1;
        private PixelInfo pixels;


        @Override
        String getAPIURLString() {
            return url;
        }

        long getAcquisitionDate() {
            return acquisitionDate;
        }

        int[] getImageDimensions() {
            return pixels.getImageDimensions();
        }

        PhysicalSize[] getPhysicalSizes() {
            return pixels.getPhysicalSizes();
        }

        String getPixelType() {
            return pixels.getPixelType();
        }

        public Image(String url, ImageData imageData, long id, OmeroRawObjectType type, OmeroRawClient client, OmeroRawObject parent) throws DSOutOfServiceException, ServerError {
            this.url = url;
            this.acquisitionDate = imageData.getAcquisitionDate()==null ? -1 : imageData.getAcquisitionDate().getTime();
            PixelsData pixData = imageData.getDefaultPixels();

            PixelInfo pixelInfo = new PixelInfo(pixData.getSizeX(), pixData.getSizeY(), pixData.getSizeC(), pixData.getSizeZ(), pixData.getSizeT(),
                    pixData.asPixels().getPhysicalSizeX()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeX().getUnit().toString(), pixData.asPixels().getPhysicalSizeX().getValue()),
                    pixData.asPixels().getPhysicalSizeY()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeY().getUnit().toString(), pixData.asPixels().getPhysicalSizeY().getValue()),
                    pixData.asPixels().getPhysicalSizeZ()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeZ().getUnit().toString(), pixData.asPixels().getPhysicalSizeZ().getValue()),
                    new ImageType(pixData.getPixelType()));

            this.pixels = pixelInfo;

            super.setId(id);
            super.setName(imageData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            omero.model.Experimenter user = client.getGateway().getAdminService(client.getContext()).getExperimenter(imageData.getOwner().getId());
            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(imageData.getGroupId(),
                    client.getGateway().getAdminService(client.getContext()).getGroup(imageData.getGroupId()).getName().getValue()));
        }
    }


    static class Owner {
        private long id;
        private String firstName = "";
        private String middleName = "";
        private String lastName = "";
        private String emailAddress = "";
        private String institution = "";
        private String username = "";

        // Singleton (with static factory)
        private static final Owner ALL_MEMBERS = new Owner(-1, "All members", "", "", "", "", "");

        public Owner(long id, String firstName, String middleName, String lastName, String emailAddress, String institution, String username) {
            this.id = Objects.requireNonNull(id);
            this.firstName = Objects.requireNonNull(firstName);
            this.middleName = Objects.requireNonNull(middleName);
            this.lastName = Objects.requireNonNull(lastName);

            this.emailAddress = emailAddress;
            this.institution = institution;
            this.username = username;
        }

        String getName() {
            // We never know if a deserialized Owner will have all the necessary information
            firstName = firstName == null ? "" : firstName;
            middleName = middleName == null ? "" : middleName;
            lastName = lastName == null ? "" : lastName;
            return firstName + " " + (middleName.isEmpty() ? "" : middleName + " ") + lastName;
        }

        long getId() {
            return id;
        }

        /**
         * Dummy {@code Owner} object (singleton instance) to represent all owners.
         * @return owner
         */
        static Owner getAllMembersOwner() {
            return ALL_MEMBERS;
        }

        @Override
        public String toString() {
            List<String> list = new ArrayList<>(Arrays.asList("Owner: " + getName(), emailAddress, institution, username));
            list.removeAll(Arrays.asList("", null));
            return String.join(", ", list);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Owner))
                return false;
            return ((Owner)obj).id == this.id;
        }
    }

    static class Group {
        private long id;
        private String name;

        // Singleton (with static factory)
        private static final Group ALL_GROUPS = new Group(-1, "All groups");


        public Group(long id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * Dummy {@code Group} object (singleton instance) to represent all groups.
         * @return group
         */
        public static Group getAllGroupsGroup() {
            return ALL_GROUPS;
        }

        String getName() {
            return name;
        }

        long getId() {
            return id;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Group))
                return false;
            return ((Group)obj).id == this.id;
        }
    }

    static class PixelInfo {
        private int width;
        private int height;
        private int z;
        private int c;
        private int t;
        private PhysicalSize physicalSizeX;
        private PhysicalSize physicalSizeY;
        private PhysicalSize physicalSizeZ;
        private ImageType imageType;

        int[] getImageDimensions() {
            return new int[] {width, height, c, z, t};
        }

        PhysicalSize[] getPhysicalSizes() {
            return new PhysicalSize[] {physicalSizeX, physicalSizeY, physicalSizeZ};
        }

        String getPixelType() {
            return imageType.getValue();
        }

        public PixelInfo(int width, int height,int c, int z, int t, PhysicalSize pSizeX, PhysicalSize pSizeY, PhysicalSize pSizeZ, ImageType imageType){
            this.width = width;
            this.height = height;
            this.c = c;
            this.t = t;
            this.z = z;
            this.physicalSizeX = pSizeX;
            this.physicalSizeY = pSizeY;
            this.physicalSizeZ = pSizeZ;
            this.imageType = imageType;
        }
    }

    static class PhysicalSize {

        private String symbol;
        private double value;


        String getSymbol() {
            return symbol;
        }
        double getValue() {
            return value;
        }

        public PhysicalSize(String symbol, double value){
            this.symbol = symbol;
            this.value = value;
        }

    }

    static class ImageType {

        private String value;

        String getValue() {
            return value;
        }

        public ImageType(String value){
            this.value = value;
        }

    }


    /**
     * Both in OmeroAnnotations and in OmeroObjects.
     */
    static class Permission {

        @SerializedName(value = "canDelete")
        private boolean canDelete;

        @SerializedName(value = "canAnnotate")
        private boolean canAnnotate;

        @SerializedName(value = "canLink")
        private boolean canLink;

        @SerializedName(value = "canEdit")
        private boolean canEdit;

        // Only in OmeroObjects
        @SerializedName(value = "isUserWrite")
        private boolean isUserWrite;

        @SerializedName(value = "isUserRead")
        private boolean isUserRead;

        @SerializedName(value = "isWorldWrite")
        private boolean isWorldWrite;

        @SerializedName(value = "isWorldRead")
        private boolean isWorldRead;

        @SerializedName(value = "isGroupWrite")
        private boolean isGroupWrite;

        @SerializedName(value = "isGroupRead")
        private boolean isGroupRead;

        @SerializedName(value = "isGroupAnnotate")
        private boolean isGroupAnnotate;

        @SerializedName(value = "perm")
        private String perm;
    }


    static class Link {

        @SerializedName(value = "id")
        private int id;

        @SerializedName(value = "owner")
        private Owner owner;

        Owner getOwner() {
            return owner;
        }
    }


    static class Experimenter {

        @SerializedName(value = "id")
        private int id;

        @SerializedName(value = "omeName")
        private String omeName;

        @SerializedName(value = "firstName")
        private String firstName;

        @SerializedName(value = "lastName")
        private String lastName;

        /**
         * Return the Id of this {@code Experimenter}.
         * @return id
         */
        int getId() {
            return id;
        }

        /**
         * Return the full name (first name + last name) of this {@code Experimenter}.
         * @return full name
         */
        String getFullName() {
            return firstName + " " + lastName;
        }
    }
}

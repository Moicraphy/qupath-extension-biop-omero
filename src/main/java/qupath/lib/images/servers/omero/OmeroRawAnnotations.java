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

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import omero.ServerError;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.model.AnnotationData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PermissionData;
import omero.gateway.model.TagAnnotationData;
import omero.model.IObject;
import omero.model.NamedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import qupath.lib.images.servers.omero.OmeroRawObjects.Experimenter;
import qupath.lib.images.servers.omero.OmeroRawObjects.Link;
import qupath.lib.images.servers.omero.OmeroRawObjects.Owner;
import qupath.lib.images.servers.omero.OmeroRawObjects.Permission;

/**
 * Class representing OMERO annotations.
 * <p>
 * OMERO annotations are <b>not</b> similar to QuPath annotations. Rather, they
 * represent some type of metadata visible on the right pane in the OMERO Webclient.
 *
 * Note: Tables annotations are ignored.
 *
 * @author Melvin Gelbard
 */
// TODO: Handle Table annotations
final class OmeroRawAnnotations {

    private final static Logger logger = LoggerFactory.getLogger(OmeroAnnotations.class);

    public static enum OmeroRawAnnotationType {
        TAG("TagAnnotationI", "tag"),
        MAP("MapAnnotationI", "map"),
        ATTACHMENT("FileAnnotationI", "file"),
        COMMENT("CommentAnnotationI", "comment"),
        RATING("LongAnnotationI", "rating"),
        UNKNOWN("Unknown", "unknown");

        private final String name;
        private final String urlName;
        private OmeroRawAnnotationType(String name, String urlName) {
            this.name = name;
            this.urlName = urlName;
        }

        public static OmeroRawAnnotationType fromString(String text) {
            for (var type : OmeroRawAnnotationType.values()) {
                if (type.name.equalsIgnoreCase(text) || type.urlName.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        public String toURLString() {
            return urlName;
        }

        @Override
        public String toString() {
            return name;
        }
    }


    @SerializedName(value = "annotations")
    private final List<OmeroAnnotation> annotations;

    @SerializedName(value = "experimenters")
    private final List<Experimenter> experimenters;

    private final OmeroRawAnnotationType type;

    protected OmeroRawAnnotations(List<OmeroAnnotation> annotations, List<Experimenter> experimenters, OmeroRawAnnotationType type) {
        this.annotations = Objects.requireNonNull(annotations);
        this.experimenters = Objects.requireNonNull(experimenters);
        this.type = type;
    }

    /**
     * Static factory method to get all annotations & experimenters in a single {@code OmeroAnnotations} object.
     * @param json
     * @return OmeroAnnotations
     * @throws IOException
     */
    public static OmeroRawAnnotations getOmeroAnnotations(OmeroRawClient client, OmeroRawObjects.OmeroRawObject obj, OmeroRawAnnotationType annotatationType, List<?> annotations) throws IOException {
        List<OmeroAnnotation> omeroAnnotations = new ArrayList<>();
        List<Experimenter> experimenters = new ArrayList<>();

        switch(annotatationType){
            case TAG:
                List<TagAnnotationData> tags = (List<TagAnnotationData>)annotations.stream().filter(tag-> tag instanceof TagAnnotationData).collect(Collectors.toList());
                tags.forEach(tag-> {
                    omeroAnnotations.add(new TagAnnotation(client, tag));
                    experimenters.add(new Experimenter(tag.getOwner().asExperimenter()));
                });
                break;
            case MAP:
                List<MapAnnotationData> kvps = (List<MapAnnotationData>)annotations.stream().filter(map-> map instanceof MapAnnotationData).collect(Collectors.toList());
                System.out.println("Key values paisrs : "+kvps);
                kvps.forEach(kvp-> {
                    omeroAnnotations.add(new MapAnnotation(client, kvp));
                    experimenters.add(new Experimenter(kvp.getOwner().asExperimenter()));
                });
                break;
            default:

        }
        return new OmeroRawAnnotations(omeroAnnotations, experimenters, annotatationType);
    }

    /**
     * Return all {@code OmeroAnnotation} objects present in this {@code OmeroAnnotation}s object.
     * @return annotations
     */
    public List<OmeroAnnotation> getAnnotations() {
        return annotations;
    }

    /**
     * Return all {@code Experimenter}s present in this {@code OmeroAnnotations} object.
     * @return experimenters
     */
    public List<Experimenter> getExperimenters() {
        return experimenters;
    }

    /**
     * Return the type of the {@code OmeroAnnotation} objects present in this {@code OmeroAnnotations} object.
     * @return type
     */
    public OmeroRawAnnotationType getType() {
        return type;
    }

    /**
     * Return the number of annotations in this {@code OmeroAnnotations} object.
     * @return size
     */
    public int getSize() {
        return annotations.stream().mapToInt(e -> e.getNInfo()).sum();
    }

  /*  static class GsonOmeroAnnotationDeserializer implements JsonDeserializer<OmeroAnnotation> {

        @Override
        public OmeroAnnotation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {

            var type = OmeroRawAnnotationType.fromString(((JsonObject)json).get("class").getAsString());

            OmeroAnnotation omeroAnnotation;
            if (type == OmeroRawAnnotationType.TAG)
                omeroAnnotation = context.deserialize(json, TagAnnotation.class);
            else if (type == OmeroRawAnnotationType.MAP)
                omeroAnnotation = context.deserialize(json, MapAnnotation.class);
            else if (type == OmeroRawAnnotationType.ATTACHMENT)
                omeroAnnotation = context.deserialize(json, FileAnnotation.class);
            else if (type == OmeroRawAnnotationType.COMMENT)
                omeroAnnotation = context.deserialize(json, CommentAnnotation.class);
            else if (type == OmeroRawAnnotationType.RATING)
                omeroAnnotation = context.deserialize(json, LongAnnotation.class);
            else {
                logger.warn("Unsupported type {}", type);
                return null;
            }

            return omeroAnnotation;
        }
    }*/

    abstract static class OmeroAnnotation {

        private int id;
        private Owner owner;
        private Permission permissions;
        private String type;
        private Link link;

        /**
         * Set the id of this object
         * @param id
         */
        void setId(int id) {
            this.id = id;
        }


        /**
         * Set the owner of this object
         * @param owner
         */
        void setOwner(Owner owner) {
            this.owner = owner;
        }

        /**
         * Set the type of this object
         * @param type
         */
        void setType(String type) {
            this.type = OmeroRawAnnotationType.fromString(type).toString();
        }

        /**
         * Set the permissions of this object
         * @param permissions
         */
        void setPermissions(Permission permissions) { this.permissions = permissions; }

        /**
         * Set the permissions of this object
         * @param link
         */
        void setLink(Link link) { this.link = link; }

        /**
         * Return the {@code OmeroAnnotationType} of this {@code OmeroAnnotation} object.
         * @return omeroAnnotationType
         */
        public OmeroRawAnnotationType getType() {
            return OmeroRawAnnotationType.fromString(type);
        }


        /**
         * Return the owner of this {@code OmeroAnnotation}. Which is the creator of this annotation
         * but not necessarily the person that added it.
         * @return creator of this annotation
         */
        public Owner getOwner() {
            return owner;
        }

        /**
         * Return the {@code Owner} that added this annotation. This is <b>not</b> necessarily
         * the same as the owner <b>of</b> the annotation.
         * @return owner who added this annotation
         */
        public Owner addedBy() {
            return link.getOwner();
        }

        /**
         * Return the number of 'fields' within this {@code OmeroAnnotation}.
         * @return number of fields
         */
        public int getNInfo() {
            return 1;
        }
    }

    /**
     * 'Tags'
     */
    static class TagAnnotation extends OmeroAnnotation {

        private String value;

        protected String getValue() {
            return value;
        }

        public TagAnnotation(OmeroRawClient client,TagAnnotationData tag) {
            this.value = tag.getTagValue();
            super.setId((int)tag.getId());
            super.setType(OmeroRawAnnotationType.TAG.toString());

            omero.model.Experimenter user = tag.getOwner().asExperimenter();
            Owner owner = new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = tag.getPermissions();
            super.setPermissions(new Permission(permissions, client));
            super.setLink(new Link(owner));
        }
}

    /**
     * 'Key-Value Pairs'
     */
    static class MapAnnotation extends OmeroAnnotation {


        private Map<String, String> values;

        protected Map<String, String> getValues() {
            return values;
        }

        @Override
        public int getNInfo() {
            return values.size();
        }

        public MapAnnotation(OmeroRawClient client,MapAnnotationData kvp) {

            System.out.println("Kvp = "+kvp);
            List<NamedValue> data = (List)kvp.getContent();
            System.out.println("data : "+data);
            this.values = new HashMap<>();
            for (NamedValue next : data) {

                this.values.put(next.name, next.value);
            }

            System.out.println("this.values : "+this.values);
            super.setId((int)kvp.getId());
            super.setType(OmeroRawAnnotationType.MAP.toString());

            omero.model.Experimenter user = kvp.getOwner().asExperimenter();
            Owner owner = new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = kvp.getPermissions();
            super.setPermissions(new Permission(permissions, client));
            super.setLink(new Link(owner));
        }
    }


    /**
     * 'Attachments'
     */
    static class FileAnnotation extends OmeroAnnotation {

        @SerializedName(value = "file")
        private Map<String, String> map;

        protected String getFilename() {
            return map.get("name");
        }

        /**
         * Size in bits.
         * @return size
         */
        protected long getFileSize() {
            return Long.parseLong(map.get("size"));
        }

        protected String getMimeType() {
            return map.get("mimetype");
        }
    }

    /**
     * 'Comments'
     */
    static class CommentAnnotation extends OmeroAnnotation {

        private String value;

        protected String getValue() {
            return value;
        }
    }

    /**
     * 'Comments'
     */
    static class LongAnnotation extends OmeroAnnotation {

        private short value;

        protected short getValue() {
            return value;
        }
    }
}



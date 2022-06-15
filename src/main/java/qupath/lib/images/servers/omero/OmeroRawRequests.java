package qupath.lib.images.servers.omero;

import com.google.gson.JsonObject;
import loci.common.services.ServiceException;
import omero.ServerError;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ImageData;
import omero.model.Dataset;
import omero.model.ExperimenterGroup;
import omero.model.GroupExperimenterMap;
import omero.model.IObject;
import qupath.lib.io.GsonTools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class OmeroRawRequests {

    public static Collection<ImageData> getOrphanedImages(OmeroRawClient client) throws DSOutOfServiceException, ServerError {

        List<ExperimenterGroup> groups = client.getGateway().getAdminService(client.getContext()).lookupGroups();
        Collection<ImageData> map = new ArrayList<>();
        groups.forEach(group-> {
            // new security context to access data from other groups
            SecurityContext ctx = new SecurityContext(group.getId().getValue());

            // get all members of the current group
            List<GroupExperimenterMap> experimentersByGroup = group.copyGroupExperimenterMap();

            for (GroupExperimenterMap experimenter : experimentersByGroup) {
                long userId = experimenter.getChild().getId().getValue();

                try {
                    map.addAll(client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(ctx, userId));

                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return map;
    }

    public static Collection<DatasetData> getOrphanedDatasets(OmeroRawClient client) throws DSOutOfServiceException, ServerError {
        List<ExperimenterGroup> groups = client.getGateway().getAdminService(client.getContext()).lookupGroups();
        Collection<DatasetData> map = new ArrayList<>();

        groups.forEach(group-> {
            // new security context to access data from other groups
            SecurityContext ctx = new SecurityContext(group.getId().getValue());

            try {
                Collection<DatasetData> datasets = client.getGateway().getFacility(BrowseFacility.class).getDatasets(ctx);
                datasets.forEach(dataset-> {
                    try {
                        if (client.getGateway().getQueryService(ctx).findAllByQuery("select link.parent from ProjectDatasetLink as link " +
                                "where link.child=" + dataset.getId(), null).isEmpty()) {
                            map.add(dataset);
                        }
                    } catch (ServerError | DSOutOfServiceException e) {
                        throw new RuntimeException(e);
                    }
                });
                /*
                for(DatasetData dataset : datasets) {
                    List<IObject> os = client.getGateway().getQueryService(ctx).findAllByQuery("select link.parent from ProjectDatasetLink as link " +
                            "where link.child=" + dataset.getId(), null);
                    if(os.stream().filter(e->e.))*/
              //  System.out.println(map);

            } catch (DSOutOfServiceException | ExecutionException | DSAccessException e) {
                throw new RuntimeException(e);
            }

        });

        return map;
    }
}

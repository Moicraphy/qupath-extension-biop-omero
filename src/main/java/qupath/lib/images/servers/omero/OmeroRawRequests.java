package qupath.lib.images.servers.omero;

import com.google.gson.JsonObject;
import omero.ServerError;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.ImageData;
import omero.model.ExperimenterGroup;
import omero.model.GroupExperimenterMap;
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
                    map.addAll(client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(/*client.getContext()*/ ctx, userId));
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        //String username = client.getGateway().getLoggedInUser().getUserName();
        //long userID = client.getGateway().getUserDetails(client.getContext(), username).getId();
        //Collection<ImageData> map = client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getContext(), userID);

        return map;
    }

   /* public static Collection<ImageData> getOrphanedDatasets(OmeroRawClient client) throws ExecutionException, DSOutOfServiceException {

        String username = client.getGateway().getLoggedInUser().getUserName();
        long userID = client.getGateway().getUserDetails(client.getContext(), username).getId();
        Collection<ImageData> map = client.getGateway().getFacility(BrowseFacility.class).getDatasets(client.getContext()).iterator().next().asDataset()

        return map;
    }*/
}

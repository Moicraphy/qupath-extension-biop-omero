package qupath.lib.images.servers.omero;

import com.google.gson.JsonObject;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.ImageData;
import qupath.lib.io.GsonTools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.concurrent.ExecutionException;

public class OmeroRawRequests {

    public static Collection<ImageData> getOrphanedImages(OmeroRawClient client) throws ExecutionException, DSOutOfServiceException {

        String username = client.getGateway().getLoggedInUser().getUserName();
        long userID = client.getGateway().getUserDetails(client.getContext(), username).getId();
        Collection<ImageData> map = client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getContext(), userID);

        return map;
    }

   /* public static Collection<ImageData> getOrphanedDatasets(OmeroRawClient client) throws ExecutionException, DSOutOfServiceException {

        String username = client.getGateway().getLoggedInUser().getUserName();
        long userID = client.getGateway().getUserDetails(client.getContext(), username).getId();
        Collection<ImageData> map = client.getGateway().getFacility(BrowseFacility.class).getDatasets(client.getContext()).iterator().next().asDataset()

        return map;
    }*/
}

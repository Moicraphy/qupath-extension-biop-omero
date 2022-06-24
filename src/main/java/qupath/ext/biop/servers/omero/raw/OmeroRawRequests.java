package qupath.ext.biop.servers.omero.raw;

import omero.RLong;
import omero.ServerError;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ImageData;
import omero.model.IObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class OmeroRawRequests {

    public static Collection<ImageData> getOrphanedImages(OmeroRawClient client, SecurityContext groupCtx) throws DSOutOfServiceException, ServerError {

        //System.out.println("OmeroRawToools - getOrphanedImages - Begin getLookupGroups");
       // long time = System.currentTimeMillis();
       // List<ExperimenterGroup> groups = client.getGateway().getAdminService(client.getContext()).lookupGroups();
        //System.out.println("OmeroRawToools - getOrphanedImages - End getLookupGroups : "+(System.currentTimeMillis()-time));

        Collection<ImageData> map = new ArrayList<>();
       // groups.forEach(group-> {

            // get all members of the current group
           // List<GroupExperimenterMap> experimentersByGroup = group.copyGroupExperimenterMap();

           // for (GroupExperimenterMap experimenter : experimentersByGroup) {
            //    long userId = experimenter.getChild().getId().getValue();

                try {
                    //System.out.println("OmeroRawToools - getOrphanedImages - Begin getOrphanedImages from OMERO");
                    long time2 = System.currentTimeMillis();
                    map.addAll(client.getGateway().getFacility(BrowseFacility.class).getOrphanedImages(groupCtx, -1));
                   // System.out.println("OmeroRawToools - getOrphanedImages - End getOrphanedImages from OMERO : "+(System.currentTimeMillis()-time2));
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
        //    }
      //  });

        return map;
    }

    public static Collection<DatasetData> getOrphanedDatasets(OmeroRawClient client, SecurityContext groupCtx) throws DSOutOfServiceException, ServerError {
        Collection<DatasetData> orphanedDatasets;

            try {
                List<IObject> datasetObjects = client.getGateway().getQueryService(groupCtx).findAllByQuery("select dataset from Dataset as dataset " +
                        "left outer join fetch dataset.details.owner " +
                        "where not exists (select obl from " +
                        "ProjectDatasetLink as obl where obl.child = dataset.id) "
                        , null);

                List<Long> datasetIds = datasetObjects.stream().map(IObject::getId).map(RLong::getValue).collect(Collectors.toList());
                orphanedDatasets = client.getGateway().getFacility(BrowseFacility.class).getDatasets(groupCtx,datasetIds);

            } catch (DSOutOfServiceException | ExecutionException | DSAccessException e) {
                throw new RuntimeException(e);
            }

        return orphanedDatasets;
    }
}

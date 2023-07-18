package qupath.ext.biop.servers.omero.raw;

import omero.gateway.model.ImageData;
import omero.gateway.model.TableData;
import omero.gateway.model.TableDataColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.scripting.QP;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UtilityTools {
    private final static Logger logger = LoggerFactory.getLogger(UtilityTools.class);
    protected static final String numericValueIdentifier = "\\$";
    protected static final String imageIDHeaderSummaryTable = numericValueIdentifier + "Image_ID";
    protected static final String MS_OMERO_TABLE = "OMERO.tables";

    protected static File buildCSVFileFromListsOfStrings(List<List<String>> parentTable, List<String> headers, String name){
        StringBuilder csvContent = new StringBuilder();
        int nRows = parentTable.get(0).size();

        // add the headers
        headers.forEach(item -> csvContent.append(item.replace(GeneralTools.micrometerSymbol(),"um").replace(UtilityTools.numericValueIdentifier,"")).append(","));
        csvContent.delete(csvContent.lastIndexOf(","), csvContent.lastIndexOf(","));
        csvContent.append("\n");

        // add the table
        for (int i = 0 ; i < nRows ; i++) {
            for (List<String> strings : parentTable) {
                String item = strings.get(i);
                csvContent.append(item.replace(UtilityTools.numericValueIdentifier, "")).append(",");
            }
            csvContent.delete(csvContent.lastIndexOf(","),csvContent.lastIndexOf(","));
            csvContent.append("\n");
        }

        String path = QP.PROJECT_BASE_DIR + File.separator + name + ".csv";
        return createAndSaveFile(path, csvContent.toString());
    }

    protected static TableData buildTableData(List<List<String>> tableString, List<String> headers, String name, OmeroRawClient client){
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();

        int c = 0;

        // feature name ; here, the ImageData object is treated differently
        columns.add(new TableDataColumn("Image", c++, ImageData.class));

        // add all ImageData in teh first column (read image from OMERO only once)
        Map<String, ImageData> mapImages = new HashMap<>();
        List<Object> imageDataField = new ArrayList<>();
        for (String item : tableString.get(0)) {
            if(mapImages.containsKey(item)){
                imageDataField.add(mapImages.get(item));
            }else {
                ImageData imageData = OmeroRawTools.readOmeroImage(client, Long.parseLong(item.replace(UtilityTools.numericValueIdentifier, "")));
                imageDataField.add(imageData);
                mapImages.put(item, imageData);
            }
        }
        measurements.add(imageDataField);

        // get the table
        for (int i = 1 ; i < tableString.size() ; i++) {
            List<String> col = tableString.get(i);
            // for OMERO.Table compatibility
            String header = headers.get(i).replace("Image", "Label");
            if (header.contains(UtilityTools.numericValueIdentifier)) {
                // feature name
                columns.add(new TableDataColumn(header.replace(GeneralTools.micrometerSymbol(),"um")
                        .replace(UtilityTools.numericValueIdentifier,"")
                        .replace("/","-"), c++, Double.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value => fill the entire column
                List<Object> feature = new ArrayList<>();
                for (String item : col) {
                    feature.add(Double.parseDouble(item.replace(UtilityTools.numericValueIdentifier,"")));
                }
                measurements.add(feature);
            } else {
                // feature name
                columns.add(new TableDataColumn(header.replace(GeneralTools.micrometerSymbol(),"um")
                        .replace("/","-"), c++, String.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value => fill the entire column
                List<Object> feature = new ArrayList<>(col);
                measurements.add(feature);
            }
        }
        return new TableData(columns, measurements);
    }

    protected static File createAndSaveFile(String path, String content){

        // create the file locally
        File file = new File(path);

        try {
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(content + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            Dialogs.showErrorNotification("Write CSV file", "An error has occurred when trying to save the csv file");
            logger.error("" + e);
            logger.error(OmeroRawTools.getErrorStackTraceAsString(e));
        }

        return file;
    }
}

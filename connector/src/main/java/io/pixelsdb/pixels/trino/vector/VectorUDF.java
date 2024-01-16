package io.pixelsdb.pixels.trino.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.pixelsdb.pixels.trino.vector.exactnns.ExactNNS;
import io.trino.spi.block.Block;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.io.IOException;

import static io.trino.spi.type.DoubleType.DOUBLE;

public class VectorUDF {

    private VectorUDF() {}

    @ScalarFunction("exactNNS")
    @Description("exact nearest neighbours search")
    @SqlType(StandardTypes.JSON)
    @SqlNullable
    public static Slice exactNNS(
            @SqlNullable @SqlType("array(double)") Block trinoVector, @SqlType("integer") long columnId, @SqlType(StandardTypes.VARCHAR) Slice strForVectorDistFunc, @SqlType("integer") long k)
    {
        // prepare input vector
        double[] inputVector;
        if (trinoVector == null) {
            return null;
        }
        inputVector = new double[trinoVector.getPositionCount()];
        for (int i = 0; i < trinoVector.getPositionCount(); i++) {
            inputVector[i] = DOUBLE.getDouble(trinoVector, i);
        }

        // prepare list of files to read
        // todo how to go from (tableId, columnId) to the list of files belong to that column?
        String[] listOfFiles = new String[2];
        listOfFiles[0] = System.getenv("PIXELS_S3_TEST_BUCKET_PATH") + "exactNNS-test-file1.pxl";
        listOfFiles[1] = System.getenv("PIXELS_S3_TEST_BUCKET_PATH") + "exactNNS-test-file2.pxl";

        // prepare distance function
        VectorDistFunc vectorDistFunc =  switch (strForVectorDistFunc.toStringUtf8()) {
            case "euc" -> VectorDistFuncs::eucDist;
            case "cos" -> VectorDistFuncs::cosSim;
            case "dot" -> VectorDistFuncs::dotProd;
            default -> null;
        };

        ExactNNS exactNNS = new ExactNNS(inputVector, listOfFiles, (int)k, vectorDistFunc, (int)columnId);
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return Slices.utf8Slice(objectMapper.writeValueAsString(exactNNS.getNearestNbrs()));
        } catch(IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @ScalarFunction("eucDist")
    @Description("calculate the Euclidean distance between two vectors")
    @SqlType(StandardTypes.DOUBLE)
    @SqlNullable
    public static Double eucDist(
            @SqlNullable @SqlType("array(double)") Block vec1,
            @SqlNullable @SqlType("array(double)") Block vec2)
    {
        if (!distIsDefined(vec1, vec2)) {
            return null;
        }

        double dist = 0.0;
        for (int position = 0; position < vec1.getPositionCount(); position++) {
            //todo can also use multi threads and let different threads be responsible for different elements
            // one thread for calculating (x[1]-y[1])^2, another (x[2]-y[2))^2
            // let's keep it simple and only use single thread for now
            double xi = DOUBLE.getDouble(vec1, position);
            double yi = DOUBLE.getDouble(vec2, position);
            dist += (xi-yi)*(xi-yi);
        }
        return dist;
    }

    @ScalarFunction("dotProd")
    @Description("calculate the dot product between two vectors")
    @SqlType(StandardTypes.DOUBLE)
    @SqlNullable
    public static Double dotProd(
            @SqlNullable @SqlType("array(double)") Block vec1,
            @SqlNullable @SqlType("array(double)") Block vec2)
    {
        if (!distIsDefined(vec1, vec2)) {
            return null;
        }

        double dist = 0.0;
        for (int position = 0; position < vec1.getPositionCount(); position++) {
            //todo can also use multi threads and let different threads be responsible for different elements
            // one thread for calculating x[1]*y[1], another x[2]*y[2]
            // let's keep it simple and only use single thread for now
            double xi = DOUBLE.getDouble(vec1, position);
            double yi = DOUBLE.getDouble(vec2, position);
            dist += xi*yi;
        }
        return dist;
    }

    @ScalarFunction("cosSim")
    @Description("calculate the cosine similarity between two vectors")
    @SqlType(StandardTypes.DOUBLE)
    @SqlNullable
    public static Double cosSim(
            @SqlNullable @SqlType("array(double)") Block vec1,
            @SqlNullable @SqlType("array(double)") Block vec2)
    {
        if (!distIsDefined(vec1, vec2)) {
            return null;
        }

        double dotProd = 0.0;
        double vec1L2Norm = 0.0;
        double vec2L2Norm = 0.0;
        for (int position = 0; position < vec1.getPositionCount(); position++) {
            //todo can also use multi threads and let different threads be responsible for different elements
            // one thread for calculating x[1]*y[1], another x[2]*y[2]
            // let's keep it simple and only use single thread for now
            double xi = DOUBLE.getDouble(vec1, position);
            double yi = DOUBLE.getDouble(vec2, position);
            dotProd += xi*yi;
            vec1L2Norm += xi*xi;
            vec2L2Norm += yi*yi;
        }
        return dotProd / (Math.sqrt(vec1L2Norm) * Math.sqrt(vec2L2Norm));
    }

    private static boolean distIsDefined(Block vec1, Block vec2) {
        if (vec1!=null && vec2!=null && vec1.getPositionCount()==vec2.getPositionCount()) {
            return true;
        }
        return false;
    }
}

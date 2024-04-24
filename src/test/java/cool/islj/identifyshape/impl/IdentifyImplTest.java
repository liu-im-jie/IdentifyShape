package cool.islj.identifyshape.impl;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import cool.islj.identifyshape.entry.Point;
import cool.islj.identifyshape.entry.Segment;
import cool.islj.identifyshape.entry.Shape;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(SpringRunner.class)
@SpringBootTest
public class IdentifyImplTest {

    private IdentifyImpl identifyService = new IdentifyImpl();

    private final String TEST_DATA_FILE = "test/arrows.json";

    private final String OUTPUT_FILE_PATH = "D:/Code/test/result";

    private List<List<Point>> testData = new ArrayList<>();

    private double pixelDistance = 0d;

    @Before
    public void before() throws Exception {
        Resource resource = new ClassPathResource(TEST_DATA_FILE);

        Gson gson = new Gson();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            List jsonData = gson.fromJson(reader, List.class);
            for (Object data : jsonData) {
                if (data instanceof LinkedTreeMap<?, ?>) {
                    List<?> coords = (List<?>) ((LinkedTreeMap<?, ?>) data).get("coord");
                    pixelDistance = (double) ((LinkedTreeMap<?, ?>) data).get("distance");
                    List<Point> points = new ArrayList<>();
                    for (Object coord : coords) {
                        if (coord instanceof List<?> && ((List<?>) coord).size() >= 2) {
                            Point point = new Point(((List<Double>) coord).get(0), ((List<Double>) coord).get(1));
                            points.add(point);
                        }
                    }
                    testData.add(points);
                }
            }
        }
    }

    @Test
    public void testFlag() throws Exception {
        List<Segment> allSegments = new ArrayList<>();
        List<Point> allPoints = testData.stream().flatMap(Collection::stream).toList();
        for (int i = 0; i < testData.size(); i++) {
            List<Point> points = testData.get(i);
            writeCsv(points, null, OUTPUT_FILE_PATH + "_originData_" + i + ".csv");
            // 平滑
            points = identifyService.smooth(points, pixelDistance);
            writeCsv(points, null, OUTPUT_FILE_PATH + "_smooth_" + i + ".csv");
            // 切割
            List<Segment> segments = identifyService.split(points);
            for (int j = 0; j < segments.size(); j++) {
                Segment segment = segments.get(j);
                identifyService.judgeStraightOrCurve(segment);
                try {
                    writeCsv(segment.getAllPoints(), segment.getShape(), OUTPUT_FILE_PATH + "_split_" + i + "_" + j + ".csv");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }

            Iterator<Segment> iterator = segments.iterator();
            int j = 0;
            while (iterator.hasNext()) {
                Segment nextSegment = iterator.next();
                identifyService.extendSegment(allPoints, nextSegment);
                writeCsv(nextSegment.getAllPoints(), nextSegment.getShape(), OUTPUT_FILE_PATH + "_extent_" + i + "_" + j + ".csv");
                j++;
            }
            allSegments.addAll(segments);
        }
        // 求交点，打断
        List<Segment> newSegments = identifyService.curveIntersection(allSegments);
        for (int j = 0; j < newSegments.size(); j++) {
            Segment segment = newSegments.get(j);
            writeCsv(segment.getAllPoints(), segment.getShape(), OUTPUT_FILE_PATH + "_interrupt_" + j + ".csv");
        }

        Map<Point, List<Segment>> shapes = identifyService.constructShape(newSegments);
        AtomicInteger shapeCount = new AtomicInteger();
        shapes.values().forEach(segments -> {
            List<Point> points = segments.stream().flatMap(segment -> segment.getAllPoints().stream()).distinct().toList();
            try {
                writeCsv(points, null, OUTPUT_FILE_PATH + "_" + shapeCount.get() + "_shape.csv");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            shapeCount.getAndIncrement();
        });
    }

    public void writeCsv(List<Point> points, Shape shape, String filePath) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);

            CSVFormat csvFormat = CSVFormat.DEFAULT.withHeader("Longitude", "Latitude", "Shape");
            CSVPrinter csvPrinter = new CSVPrinter(osw, csvFormat);

            for (int i = 0; i < points.size(); i++) {
                Point point = points.get(i);
                csvPrinter.printRecord(point.getX(), point.getY(), shape == null ? "" : shape.name());
            }
            csvPrinter.flush();
            csvPrinter.close();
        }
    }
}

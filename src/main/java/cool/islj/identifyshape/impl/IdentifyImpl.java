package cool.islj.identifyshape.impl;

import com.google.common.collect.Lists;
import cool.islj.identifyshape.Config;
import cool.islj.identifyshape.entry.Envelope;
import cool.islj.identifyshape.entry.Point;
import cool.islj.identifyshape.entry.Polygon;
import cool.islj.identifyshape.entry.Segment;
import cool.islj.identifyshape.entry.Shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class IdentifyImpl {

    /**
     * 平滑坐标点
     *
     * @param originPoints  初始点集
     * @param pixelDistance 每个像素代表的距离
     * @return 平滑后的点集
     */
    public List<Point> smooth(List<Point> originPoints, double pixelDistance) {
        double threshold = Config.DEVIATION_PIXEL * pixelDistance;
        List<Point> result = new ArrayList<>();

        // 分组，每组进行平滑处理
        int begin = 0, end;
        while ((end = begin + Config.SMOOTH_INTERVAL_COUNT) < originPoints.size()) {
            // 将每组第一个点加入结果中
            Point beginPoint = originPoints.get(begin);
            result.add(beginPoint);

            // 计算每组头尾矢量的模长
            Point endPoint = originPoints.get(end);
            double dxSum = endPoint.getX() - beginPoint.getX();
            double dySum = endPoint.getY() - beginPoint.getY();
            double totalLength = Math.sqrt(Math.pow(dxSum, 2) + Math.pow(dySum, 2));

            // 判断起点到组内各点在头尾矢量方向上的垂直距离是否小于阈值
            // 如果出现垂直距离大于阈值的点，将此点加入结果集，舍弃其他点，并将此点作为下一分组的起点，重新分组
            boolean flag = false;
            for (int i = begin; i < end - 1; i++) {
                Point currentPoint = originPoints.get(i + 1);
                double dx = currentPoint.getX() - beginPoint.getX();
                double dy = currentPoint.getY() - beginPoint.getY();
                double length = Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
                // 计算此向量在头尾向量上的投影长度，即矢量点乘/模长
                double projectLength = (dxSum * dx + dySum * dy) / totalLength;
                // 计算currentPoint到头尾向量上垂直距离
                double distance = Math.sqrt(Math.pow(length, 2) - Math.pow(projectLength, 2));
                if (distance > threshold) {
                    begin = i + 1;
                    flag = true;
                    break;
                }
            }
            if (flag) {
                continue;
            }
            begin = end - 1;
        }
        // 如果最后不满一组，直接把最后一个点加入其中
        if (begin < originPoints.size()) {
            result.add(originPoints.getLast());
        }
        return new ArrayList<>(result);
    }

    /**
     * 在转折点处切割点集
     *
     * @param originPoints 初始点集
     * @return 切割后的线段点集，只包含直线和曲线
     */
    public List<Segment> split(List<Point> originPoints) {
        List<Segment> result = new ArrayList<>();
        int begin = 0;

        // 计算每三个点之间的夹角，夹角小于120°认为是出现了转折，打断
        for (int i = 1; i < originPoints.size() - 1; i++) {
            Point beforePoint = originPoints.get(i - 1);
            Point currentPoint = originPoints.get(i);
            Point nextPoint = originPoints.get(i + 1);

            double angle = calcAngle(beforePoint, currentPoint, nextPoint);

            if (Math.abs(angle) < 120 || (i < originPoints.size() - 2 &&
                    Math.abs(calcAngle(beforePoint, currentPoint, originPoints.get(i + 2))) < 120)) {
                if (i + 1 - begin > 4) {
                    // 角度小于120°认为此处存在转折，打断
                    // 这里考虑到折角点在平滑时被误删了，取下一个点再做一次判断
                    Segment newSegment = new Segment();
                    newSegment.setBeginPoint(originPoints.get(begin));
                    newSegment.setEndPoint(currentPoint);
                    newSegment.setAllPoints(originPoints.subList(begin, i + 1));
                    begin = i;
                    result.add(newSegment);
                }
            }
        }

        if (begin < originPoints.size()) {
            Segment newSegment = new Segment();
            newSegment.setBeginPoint(originPoints.get(begin));
            newSegment.setEndPoint(originPoints.getLast());
            newSegment.setAllPoints(originPoints.subList(begin, originPoints.size()));
            result.add(newSegment);
        }

        // 这里预处理下直线最后误画的勾脚，判断逻辑是：
        // 线足够长，且头尾存在线段的点数小于5，就认为是勾脚，删除
        if (result.size() > 1 && originPoints.size() > 15 &&
                result.stream().anyMatch(segment -> segment.getAllPoints().size() > 10)) {
            if (result.getFirst().getAllPoints().size() <= 4) {
                result.removeFirst();
            }
            if (result.getLast().getAllPoints().size() <= 4) {
                result.removeLast();
            }
        }
        return result;
    }

    private double calcAngle(Point beforePoint, Point currentPoint, Point nextPoint) {
        double dx1 = beforePoint.getX() - currentPoint.getX();
        double dy1 = beforePoint.getY() - currentPoint.getY();

        double dx2 = nextPoint.getX() - currentPoint.getX();
        double dy2 = nextPoint.getY() - currentPoint.getY();

        double length1 = Math.sqrt(Math.pow(dx1, 2) + Math.pow(dy1, 2));
        double length2 = Math.sqrt(Math.pow(dx2, 2) + Math.pow(dy2, 2));

        // 根据向量叉积/(向量模长1*向量模长2)得到cos值,进而求出弧度，再转为角度
        double cosValue = (dx1 * dx2 + dy1 * dy2) / (length1 * length2);
        return Math.acos(cosValue) * 180 / Math.PI;
    }

    /**
     * 判断线段是直线还是曲线
     *
     * @param segment 线段，判断结果填在此对象的shape属性上
     */
    public void judgeStraightOrCurve(Segment segment) {
        // 在该线段上平均取4-5个点，计算每两个点的斜率，如果斜率相差不大，则认为是直线，否则曲线
        // 这里考虑之前平滑时有可能把转折点去了，所以不计算头尾点，避免误差过大
        double minSlope = Double.MAX_VALUE;
        double maxSlope = 0;
        List<Point> allPoints = segment.getAllPoints();
        int totalCount = allPoints.size();
        int interval = (totalCount - 2) / 4;

        List<Point> selectPoints = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int index = 1 + i * interval;
            selectPoints.add(allPoints.get(Math.min(index, totalCount - 2)));
        }
        for (int i = 0; i < selectPoints.size() - 1; i++) {
            Point beginPoint = selectPoints.get(i);
            Point endPoint = selectPoints.get(i + 1);
            double slope = beginPoint.getX() - endPoint.getX() == 0 ? Double.MAX_VALUE :
                    (beginPoint.getY() - endPoint.getY()) / (beginPoint.getX() - endPoint.getX());
            minSlope = Math.min(minSlope, Math.abs(slope));
            maxSlope = Math.max(maxSlope, Math.abs(slope));
        }
        if (maxSlope - minSlope < 1 || 1 / minSlope - 1 / maxSlope < 1) {
            segment.setShape(Shape.STRAIGHT);
        } else {
            segment.setShape(Shape.CURVE);
        }
    }

    /**
     * 将线段两边延长，延长的长度为此图形的外接矩形长边的1/10
     *
     * @param originPoints 代表图形的点集
     * @param segment      线段
     */
    public void extendSegment(List<Point> originPoints, Segment segment) {
        // 获取图形的外接矩形
        Envelope envelope = getEnvelope(originPoints);

        // 获取延长线的长度
        double maxLength = Math.max((envelope.getXMax() - envelope.getXMin()), (envelope.getYMax() - envelope.getYMin()));
        double extensionLength = maxLength / 10;

        // 线段延长
        extendSegment(segment, extensionLength);
    }

    private Envelope getEnvelope(List<Point> originPoints) {
        Supplier<Stream<Point>> pointStream = originPoints::stream;
        Optional<Point> xMinPoint = pointStream.get().min(Comparator.comparing(Point::getX));
        Optional<Point> yMinPoint = pointStream.get().min(Comparator.comparing(Point::getY));
        Optional<Point> xMaxPoint = pointStream.get().max(Comparator.comparing(Point::getX));
        Optional<Point> yMaxPoint = pointStream.get().max(Comparator.comparing(Point::getY));

        if (xMinPoint.isPresent() && yMinPoint.isPresent() && xMaxPoint.isPresent() && yMaxPoint.isPresent()) {
            return new Envelope(xMinPoint.get().getX(), yMinPoint.get().getY(),
                    xMaxPoint.get().getX(), yMaxPoint.get().getY());
        }
        return new Envelope();
    }

    private void extendSegment(Segment segment, double extensionLength) {
        if (segment.getAllPoints().size() <= 3 || Shape.STRAIGHT.equals(segment.getShape())) {
            // 如果是直线，将头尾点向外延长
            Point[] points = extendSegment(segment.getBeginPoint(), segment.getEndPoint(), extensionLength);
            if (points.length >= 2) {
                segment.setOldBeginPoint(segment.getBeginPoint());
                segment.setOldEndPoint(segment.getEndPoint());
                segment.setBeginPoint(points[0]);
                segment.setEndPoint(points[1]);
                segment.setAllPoints(Lists.newArrayList(points[0], points[1]));
            }
        } else if (Shape.CURVE.equals(segment.getShape())) {
            // 如果是曲线，取头两个点和尾两个点分别延长
            Point beginPoint = segment.getBeginPoint();
            Point nextPoint = segment.getAllPoints().get(1);
            Point[] points = extendSegment(beginPoint, nextPoint, extensionLength);
            if (points.length >= 2) {
                segment.setOldBeginPoint(segment.getBeginPoint());
                segment.setBeginPoint(points[0]);
                segment.getAllPoints().addFirst(points[0]);
            }

            Point endPoint = segment.getEndPoint();
            Point beforePoint = segment.getAllPoints().get(segment.getAllPoints().size() - 2);
            points = extendSegment(beforePoint, endPoint, extensionLength);
            if (points.length >= 2) {
                segment.setOldEndPoint(segment.getEndPoint());
                segment.setEndPoint(points[1]);
                segment.getAllPoints().add(points[1]);
            }
        }
    }

    private Point[] extendSegment(Point beginPoint, Point endPoint, double extensionLength) {
        double dx = endPoint.getX() - beginPoint.getX();
        double dy = endPoint.getY() - beginPoint.getY();

        double length = Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
        double unitDx = dx / length;
        double unitDy = dy / length;

        double newBeginX = beginPoint.getX() - unitDx * extensionLength;
        double newBeginY = beginPoint.getY() - unitDy * extensionLength;
        double newEndX = endPoint.getX() + unitDx * extensionLength;
        double newEndY = endPoint.getY() + unitDy * extensionLength;
        return new Point[]{new Point(newBeginX, newBeginY), new Point(newEndX, newEndY)};
    }

    /**
     * 将线段按交点进行拆分
     *
     * @param segments 线段
     * @return 拆分后的线段
     */
    public List<Segment> curveIntersection(List<Segment> segments) {
        List<Segment> result = new ArrayList<>();
        List<Segment> copySegments = new ArrayList<>(segments);
        segments.forEach(targetSegment -> result.addAll(interrupt(Lists.newArrayList(targetSegment), copySegments)));
        return result;
    }

    private List<Segment> interrupt(List<Segment> targetSegments, List<Segment> interruptSegments) {
        List<Segment> result = new ArrayList<>();

        List<Segment> tempSegments = new ArrayList<>();
        Iterator<Segment> targetSegmentsIterator = targetSegments.iterator();
        while (targetSegmentsIterator.hasNext()) {
            Segment targetSegment = targetSegmentsIterator.next();
            for (Segment interruptSegment : interruptSegments) {
                List<Segment> interruptedSegments = interrupt(targetSegment, interruptSegment);
                if (interruptedSegments.size() > 1) {
                    // 产生交点打断了，跳出循环，此线段不再进行遍历
                    result.remove(targetSegment);
                    tempSegments.addAll(interruptedSegments);
                    targetSegmentsIterator.remove();
                    break;
                } else {
                    // 没有交点，暂时将其放入结果中
                    if (!result.contains(targetSegment)) {
                        result.add(targetSegment);
                    }
                }
            }
        }
        if (!tempSegments.isEmpty()) {
            result.addAll(interrupt(tempSegments, interruptSegments));
        }
        result.removeIf(segment -> {
            List<Point> points = segment.getAllPoints();
            return points.size() <= 2 &&
                    Math.abs(points.getFirst().getX() - points.getLast().getX()) <= 0.0001 &&
                    Math.abs(points.getFirst().getY() - points.getLast().getY()) <= 0.0001;
        });
        result.removeIf(segment -> (segment.getBeginPoint().getX() >= segment.getOldEndPoint().getX() && segment.getEndPoint().getX() > segment.getOldEndPoint().getX()) ||
                (segment.getBeginPoint().getX() < segment.getOldBeginPoint().getX() && segment.getEndPoint().getX() <= segment.getOldBeginPoint().getX()));
        return result;
    }

    private List<Segment> interrupt(Segment targetSegment, Segment interruptSegment) {
        List<Point> curve1 = targetSegment.getAllPoints();
        List<Point> curve2 = interruptSegment.getAllPoints();

        for (int i = 0; i < curve1.size() - 1; i++) {
            for (int j = 0; j < curve2.size() - 1; j++) {
                Point intersection = intersect(curve1.get(i), curve1.get(i + 1), curve2.get(j), curve2.get(j + 1));
                if (intersection != null && !Objects.equals(intersection.getX(), targetSegment.getBeginPoint().getX()) &&
                        !Objects.equals(intersection.getX(), targetSegment.getEndPoint().getX())) {
                    // 存在交点,且交点不是头尾点，将targetSegment按此交点打断
                    Segment newSegment1 = new Segment();
                    newSegment1.setBeginPoint(targetSegment.getBeginPoint());
                    newSegment1.setEndPoint(intersection);
                    newSegment1.setOldBeginPoint(targetSegment.getOldBeginPoint());
                    newSegment1.setOldEndPoint(targetSegment.getOldEndPoint());
                    newSegment1.setAllPoints(new ArrayList<>(curve1.subList(0, i + 1)));
                    newSegment1.getAllPoints().add(intersection);

                    Segment newSegment2 = new Segment();
                    newSegment2.setBeginPoint(intersection);
                    newSegment2.setEndPoint(targetSegment.getEndPoint());
                    newSegment2.setOldBeginPoint(targetSegment.getOldBeginPoint());
                    newSegment2.setOldEndPoint(targetSegment.getOldEndPoint());
                    newSegment2.setAllPoints(new ArrayList<>(curve1.subList(i + 1, curve1.size())));
                    newSegment2.getAllPoints().addFirst(intersection);

                    return Lists.newArrayList(newSegment2, newSegment1);
                }
            }
        }
        return Lists.newArrayList(targetSegment);
    }

    private static Point intersect(Point p1, Point p2, Point p3, Point p4) {
        double x1 = p1.getX();
        double y1 = p1.getY();
        double x2 = p2.getX();
        double y2 = p2.getY();

        double x3 = p3.getX();
        double y3 = p3.getY();
        double x4 = p4.getX();
        double y4 = p4.getY();

        double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);

        if (d == 0) {
            return null; // 两条线段平行或共线，没有交点
        }

        double px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / d;
        double py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / d;

        if (px < Math.min(x1, x2) || px > Math.max(x1, x2) || px < Math.min(x3, x4) || px > Math.max(x3, x4)) {
            return null; // 交点不在线段上
        }

        if (py < Math.min(y1, y2) || py > Math.max(y1, y2) || py < Math.min(y3, y4) || py > Math.max(y3, y4)) {
            return null; // 交点不在线段上
        }

        return new Point(px, py);
    }

    /**
     * 组合线段，看是否有线段能组成封闭图形/箭头
     *
     * @param allSegments 线段
     * @return 如果能组成封闭图形/箭头，输出图形/箭头
     */
    public Map<Point, List<Segment>> constructShape(List<Segment> allSegments) {
        Map<Point, List<Segment>> results = new HashMap<>();
        Map<Point, List<Segment>> pointToSegmentsMap = new HashMap<>();

        for (Segment segment : allSegments) {
            Point beginPoint = segment.getBeginPoint();
            Point endPoint = segment.getEndPoint();

            pointToSegmentsMap.computeIfAbsent(beginPoint, k -> new ArrayList<>()).add(segment);
            pointToSegmentsMap.computeIfAbsent(endPoint, k -> new ArrayList<>()).add(segment);
        }

        // 判断封闭图形，以每个点作为起点，进行深度优先遍历，查找能再次回到自己的路
        for (Point startPoint : pointToSegmentsMap.keySet()) {
            List<Segment> visitedSegments = new ArrayList<>();
            List<Segment> closedPath = dfs(startPoint, startPoint, visitedSegments, pointToSegmentsMap);
            if (closedPath != null) {
                if (results.values().stream().anyMatch(segments ->
                        segments.size() == closedPath.size() && new HashSet<>(segments).containsAll(closedPath))) {
                    continue;
                }
                results.put(startPoint, closedPath);
            }
        }

        // 再次判断相连的边的斜率是否近似，如果近似，合并
        Map<Point, List<Segment>> mergeResults = new HashMap<>();
        results.forEach((point, segments) -> mergeResults.put(point, mergeSegments(segments)));

        // 判断箭头
        // 判断小箭头
        // TODO

        return mergeResults;
    }

    private List<Segment> dfs(Point startPoint, Point currentPoint,
                              List<Segment> visitedSegments, Map<Point, List<Segment>> pointToSegmentsMap) {
        List<Segment> currentSegments = pointToSegmentsMap.get(currentPoint);
        for (Segment segment : currentSegments) {
            if (visitedSegments.contains(segment)) {
                continue;
            }
            Point nextPoint = segment.otherPoint(currentPoint);
            visitedSegments.add(segment);
            if (nextPoint.equals(startPoint)) {
                return new ArrayList<>(visitedSegments);
            }
            List<Segment> result = dfs(startPoint, segment.otherPoint(currentPoint), visitedSegments, pointToSegmentsMap);
            if (result != null) {
                return result;
            }
            visitedSegments.remove(segment);
        }
        return null;
    }

    private List<Segment> mergeSegments(List<Segment> segments) {
        List<Segment> mergedSegments = new ArrayList<>();
        Segment currentSegment = segments.getFirst();
        for (int i = 1; i < segments.size(); i++) {
            Segment nextSegment = segments.get(i);
            if (Shape.CURVE.equals(currentSegment.getShape())) {
                // 曲线不合并
                currentSegment = nextSegment;
                continue;
            }
            // 之前已经确保每条线段是相连的了，这里不需要重复判断，直接判断斜率是否近似即可
            Point currentBeginPoint = currentSegment.getBeginPoint();
            Point currentEndPoint = currentSegment.getEndPoint();
            Point nextBeginPoint = nextSegment.getBeginPoint();
            Point nextEndPoint = nextSegment.getEndPoint();
            double currentSlope = currentBeginPoint.getX() - currentEndPoint.getX() == 0 ? Double.MAX_VALUE :
                    (currentBeginPoint.getY() - currentEndPoint.getY()) / (currentBeginPoint.getX() - currentEndPoint.getX());
            double nextSlope = nextBeginPoint.getX() - nextEndPoint.getX() == 0 ? Double.MAX_VALUE :
                    (nextBeginPoint.getY() - nextEndPoint.getY()) / (nextBeginPoint.getX() - nextEndPoint.getX());
            if (Math.abs(Math.abs(currentSlope) - Math.abs(nextSlope)) < 1 ||
                    Math.abs(1 / Math.abs(currentSlope) - 1 / Math.abs(nextSlope)) < 1) {
                // 合并
                List<Point> currentPoints = Lists.newArrayList(currentBeginPoint, currentEndPoint);
                List<Point> nextPoints = Lists.newArrayList(nextBeginPoint, nextEndPoint);
                List<Point> intersectPoints = new ArrayList<>(currentPoints);
                intersectPoints.retainAll(nextPoints);
                currentPoints.removeAll(intersectPoints);
                nextPoints.removeAll(intersectPoints);

                Segment newSegment = new Segment();
                newSegment.setBeginPoint(currentPoints.getFirst());
                newSegment.setEndPoint(nextPoints.getFirst());
                newSegment.setAllPoints(Lists.newArrayList(newSegment.getBeginPoint(), newSegment.getEndPoint()));
                newSegment.setShape(Shape.STRAIGHT);
                currentSegment = newSegment;
            } else {
                mergedSegments.add(currentSegment);
                currentSegment = nextSegment;
            }
        }
        mergedSegments.add(currentSegment);
        return mergedSegments;
    }
}

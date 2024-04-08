package cool.islj.identifyshape.impl;

import cool.islj.identifyshape.Config;
import cool.islj.identifyshape.entry.Envelope;
import cool.islj.identifyshape.entry.Point;
import cool.islj.identifyshape.entry.Segment;
import cool.islj.identifyshape.entry.Shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

        if (begin < originPoints.size()) {
            Segment newSegment = new Segment();
            newSegment.setBeginPoint(originPoints.get(begin));
            newSegment.setEndPoint(originPoints.getLast());
            newSegment.setAllPoints(originPoints.subList(begin, originPoints.size()));
            result.add(newSegment);
        }

        // 这里预处理下直线最后误画的勾脚，判断逻辑是：
        // 线足够长，且头尾存在线段的点数小于5，就认为是勾脚，删除
        if (result.size() > 2 && originPoints.size() > 15 &&
                result.stream().anyMatch(segment -> segment.getAllPoints().size() > 10)) {
            if (result.getFirst().getAllPoints().size() <= 3) {
                result.removeFirst();
            }
            if (result.getLast().getAllPoints().size() <= 3) {
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
     * 将线段两边延长，延长的长度为此图形的外接矩形短边的1/10
     *
     * @param originPoints 代表图形的点集
     * @param segment      线段
     */
    public void extendSegment(List<Point> originPoints, Segment segment) {
        // 获取图形的外接矩形
        Envelope envelope = getEnvelope(originPoints);

        // 获取延长线的长度
        double minLength = Math.min((envelope.getXMax() - envelope.getXMin()), (envelope.getYMax() - envelope.getYMin()));
        double extensionLength = minLength / 10;

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
                segment.setBeginPoint(points[0]);
                segment.setEndPoint(points[1]);
            }
        } else if (Shape.CURVE.equals(segment.getShape())) {
            // 如果是曲线，取头两个点和尾两个点分别延长
            Point beginPoint = segment.getBeginPoint();
            Point nextPoint = segment.getAllPoints().get(1);
            Point[] points = extendSegment(beginPoint, nextPoint, extensionLength);
            if (points.length >= 2) {
                segment.setBeginPoint(points[0]);
            }

            Point endPoint = segment.getEndPoint();
            Point beforePoint = segment.getAllPoints().get(segment.getAllPoints().size() - 2);
            points = extendSegment(beforePoint, endPoint, extensionLength);
            if (points.length >= 2) {
                segment.setEndPoint(points[1]);
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
}

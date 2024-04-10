package cool.islj.identifyshape.entry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Segment {

    Point beginPoint;

    Point endPoint;

    Point oldBeginPoint;

    Point oldEndPoint;

    List<Point> allPoints;

    Shape shape;

    public Point otherPoint(Point point) {
        if (point == null) return null;
        if (point.equals(beginPoint)) return endPoint;
        if (point.equals(endPoint)) return beginPoint;
        return null;
    }

    public boolean containPoint(Point point) {
        if(point == null) return false;
        return allPoints.contains(point);
    }
}

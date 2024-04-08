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

    List<Point> allPoints;
}

package cool.islj.identifyshape.entry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@AllArgsConstructor
@EqualsAndHashCode(of = {"x", "y"})
public class Point {

    private double x;

    private double y;
}

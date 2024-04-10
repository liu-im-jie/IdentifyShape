package cool.islj.identifyshape.entry;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Objects;

@Data
@AllArgsConstructor
public class Point {

    private double x;

    private double y;


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        if (Math.abs(((Point) o).x - x) < 0.0001 && Math.abs(((Point) o).y - y) < 0.0001) {
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode((int) Math.floor(x) + (int) Math.floor(y));
    }
}

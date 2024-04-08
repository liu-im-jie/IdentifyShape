package cool.islj.identifyshape.entry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Envelope {
    double xMin;
    double yMin;
    double xMax;
    double yMax;
}

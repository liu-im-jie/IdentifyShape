package cool.islj.identifyshape.entry;

public enum Shape {

    STRAIGHT(1),
    CURVE(2);

    int value;

    Shape(int value) {
        this.value = value;
    }
}

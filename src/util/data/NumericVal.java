package util.data;

import java.math.BigDecimal;

public interface NumericVal {

    String name(); // get the name
    String group(); // get the group
    String id(); //get the id
    BigDecimal toBigDecimal(); // get the value as a BigDecimal
    String unit();
    double value(); // Get the value as a double
    int intValue(); // Get the value as an integer
    void updateValue(double val); // update the value based on the double
    default int order(){
        return -1;
    }
}

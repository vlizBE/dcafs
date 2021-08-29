package util.data;

import java.math.BigDecimal;

public interface NumericVal {

    String name();
    String group();
    String id();
    BigDecimal toBigDecimal();

    double value();
    void updateValue(double val);

}

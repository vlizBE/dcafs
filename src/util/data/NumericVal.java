package util.data;

import java.math.BigDecimal;

public interface NumericVal {

    String getName();
    String getGroup();
    String getID();
    BigDecimal toBigDecimal();

    double getValue();
    void setValue( double val);

}

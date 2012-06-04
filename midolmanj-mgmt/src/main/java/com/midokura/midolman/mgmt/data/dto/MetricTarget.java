/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * MetricTarget represent an object for which we can collect metrics.
 * Date: 5/25/12
 */

@XmlRootElement
public class MetricTarget {
    /**
     * the UUID of the object to which the metric refers
     */
    UUID targetIdentifier;
    /**
     * the type of the object
     */
    String type;

    public UUID getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setTargetIdentifier(UUID targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

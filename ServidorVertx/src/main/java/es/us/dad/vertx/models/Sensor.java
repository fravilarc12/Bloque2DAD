package es.us.dad.vertx.models;

import java.util.Objects;

public class Sensor {
    private Integer id; // ID único del dato (autoincremental en BBDD)
    private String sensorId; // ID único del dispositivo físico
    private Long timestamp;
    private String type;
    private Double value;
    private Integer idGroup;

    public Sensor() {
    }

    public Sensor(Integer id, String sensorId, Long timestamp, String type, Double value, Integer idGroup) {
        this.id = id;
        this.sensorId = sensorId;
        this.timestamp = timestamp;
        this.type = type;
        this.value = value;
        this.idGroup = idGroup;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Integer getIdGroup() {
        return idGroup;
    }

    public void setIdGroup(Integer idGroup) {
        this.idGroup = idGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Sensor sensor = (Sensor) o;
        return Objects.equals(id, sensor.id) && Objects.equals(sensorId, sensor.sensorId) && Objects.equals(timestamp, sensor.timestamp) && Objects.equals(type, sensor.type) && Objects.equals(value, sensor.value) && Objects.equals(idGroup, sensor.idGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sensorId, timestamp, type, value, idGroup);
    }

    @Override
    public String toString() {
        return "Sensor{" +
                "id=" + id +
                ", sensorId='" + sensorId + '\'' +
                ", timestamp=" + timestamp +
                ", type='" + type + '\'' +
                ", value=" + value +
                ", idGroup=" + idGroup +
                '}';
    }
}

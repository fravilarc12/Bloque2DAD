package es.us.dad.vertx.models;

import java.util.Objects;

public class Actuator {
    private Integer id; // ID único del dato (autoincremental en BBDD)
    private String actuatorId; // ID único del dispositivo físico
    private ActuatorType type;
    private Double status;
    private Integer idGroup;

    public Actuator() {
    }

    public Actuator(Integer id, String actuatorId, ActuatorType type, Double status, Integer idGroup) {
        this.id = id;
        this.actuatorId = actuatorId;
        this.type = type;
        this.status = status;
        this.idGroup = idGroup;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getActuatorId() {
        return actuatorId;
    }

    public void setActuatorId(String actuatorId) {
        this.actuatorId = actuatorId;
    }

    public ActuatorType getType() {
        return type;
    }

    public void setType(ActuatorType type) {
        this.type = type;
    }

    public Double getStatus() {
        return status;
    }

    public void setStatus(Double status) {
        this.status = status;
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
        Actuator actuator = (Actuator) o;
        return Objects.equals(id, actuator.id) && Objects.equals(actuatorId, actuator.actuatorId) && type == actuator.type && Objects.equals(status, actuator.status) && Objects.equals(idGroup, actuator.idGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, actuatorId, type, status, idGroup);
    }

    @Override
    public String toString() {
        return "Actuator{" +
                "id=" + id +
                ", actuatorId='" + actuatorId + '\'' +
                ", type=" + type +
                ", status=" + status +
                ", idGroup=" + idGroup +
                '}';
    }
}

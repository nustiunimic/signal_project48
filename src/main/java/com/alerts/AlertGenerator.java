package com.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import com.data_management.DataStorage; 
import com.data_management.Patient;
import com.data_management.PatientRecord;

/* packets generally should not use _ but it gives error otherwise */

/**
 * The {@code AlertGenerator} class is responsible for monitoring patient data
 * and generating alerts when certain predefined conditions are met. This class
 * relies on a {@link DataStorage} instance to access patient data and evaluate
 * it against specific health criteria.
 * 
 * The class is designed to be extensible and allows adding new alert conditions,
 * based on different types of patient data(heart rate/blood pressure etc.).
 * 
 * When alert conditions are met (abnormal heart rate/ oxygen dangerous levels),
 * the alert system is triggered. This can notify the medical staff, logging or
 * any other action needed.
 * 
 * For example , the following conditions are checked:
 * Heart rate exceeding 120bpm and Oxygen level falling below 90% triggers an alert.
 * 
 * @author Octavian
 */
public class AlertGenerator {

    /** The data storage system used to retrieve patient data for evaluation. */
    
    private final DataStorage dataStorage;

        private List<Alert> triggeredAlerts = new ArrayList<>();

    //new alert type factories included

        private final BloodPressureAlertFactory bloodPressureAlertFactory;
        private final BloodOxygenAlertFactory bloodOxygenAlertFactory;
        private final ECGAlertFactory ecgAlertFactory;
    
    /* datastorage should be final */

    /**
     * Constructs an {@code AlertGenerator} with a specified {@code DataStorage}.
     * The {@code DataStorage} is used to retrieve patient data that this class
     * will monitor and evaluate.
     *
     * @param dataStorage the data storage system that provides access to patient data
     */
    
     /* improved spacing for better reading  */

     //adding the strategies
     private Map<String, AlertStrategy> alertStrategies;

    public AlertGenerator(DataStorage dataStorage) {
        this.dataStorage = dataStorage;

        //factory initialization
        this.bloodPressureAlertFactory= new BloodPressureAlertFactory();
        this.bloodOxygenAlertFactory= new BloodOxygenAlertFactory();
        this.ecgAlertFactory= new ECGAlertFactory();

        //initialization of strategies        
        this.alertStrategies = new HashMap<>();
        alertStrategies.put("SystolicBloodPressure", new BloodPressureStrategy(90, 180, "Systolic"));
        alertStrategies.put("DiastolicBloodPressure", new BloodPressureStrategy(60, 120, "Diastolic"));
        alertStrategies.put("HeartRate", new HeartRateStrategy(120));
        alertStrategies.put("OxygenLevel", new OxygenSaturationStrategy(90));
    }

    //setting up the strategy for a type of measurment
    public void setStrategy(String metricType, AlertStrategy strategy){
        alertStrategies.put(metricType, strategy);
    }

    /**
     * Evaluates the specified patient's data to determine if any alert conditions
     * are met. If a condition is met, an alert is triggered via the
     * {@link #triggerAlert}
     * method. This method should define the specific conditions under which an
     * alert will be triggered. //improved spacing
     *
     * @param patient the patient data to evaluate for alert conditions
     */

    public void evaluateData(Patient patient) {
        // Implementation goes here
        long now = System.currentTimeMillis();
        long lastHour = now - 60 * 60 * 1000;

        /** Get all patient records from the last hour */
        List<PatientRecord> recentRecords =   dataStorage.getRecords(patient.getPatientId(), lastHour, now);
        List<Double> systolicBP = new ArrayList<>();
        List<Double> diastolicBP = new ArrayList<>();
        List<Double> ecgValues = new ArrayList<>();

        PatientRecord previousOxygen = null;
        boolean lowBP = false;
        boolean lowOxygen = false;

        /** Iterated through the records to check conditions. */
        for (PatientRecord record : recentRecords) {
            String type = record.getRecordType();
            double value = record.getMeasurementValue();
            long timestamp = record.getTimestamp();

            /** Checks for high heart rate condition */
            
            //adapted to the new factory method 
            if (alertStrategies.containsKey(type)) {
                AlertStrategy strategy = alertStrategies.get(type);
                
                if (strategy.checkAlert(String.valueOf(patient.getPatientId()), value, timestamp)) {
                    String condition = strategy.getConditionDescription(value);
                    Alert alert = null;
                    //chooses the right factory based on the measurments                
                    if (type.equals("HeartRate")) {
                        alert = ecgAlertFactory.createAlert(
                            String.valueOf(patient.getPatientId()),
                            condition,
                            timestamp
                        );
                    } else if (type.equals("OxygenLevel")) {
                        alert = bloodOxygenAlertFactory.createAlert(
                            String.valueOf(patient.getPatientId()),
                            condition,
                            timestamp
                        );
                    } else if (type.contains("BloodPressure")) {
                        alert = bloodPressureAlertFactory.createAlert(
                            String.valueOf(patient.getPatientId()),
                            condition,
                            timestamp
                        );
                    }
                    
                    if (alert != null) {
                        triggerAlert(alert);
                    }
                }
            }
            
            if (type.equals("SystolicBloodPressure")) {
                systolicBP.add(value);
                if (value < 90) lowBP = true;
            } else if (type.equals("DiastolicBloodPressure")) {
                diastolicBP.add(value);
            } else if (type.equals("OxygenLevel")) {
                if (value < 92) lowOxygen = true;
                
                //calculating fast oxygen drops
                if (previousOxygen != null && timestamp - previousOxygen.getTimestamp() <= 10 * 60 * 1000) {
                    double drop = previousOxygen.getMeasurementValue() - value;
                    if (drop >= 5) {
                        Alert alert = bloodOxygenAlertFactory.createAlert(
                            String.valueOf(patient.getPatientId()),
                            "Rapid Oxygen Drop: -" + drop + "%",
                            timestamp
                        );
                        triggerAlert(alert);
                    }
                }
                previousOxygen = record;
            } else if (type.equals("ECG")) {
                ecgValues.add(value);
            } else if (type.equalsIgnoreCase("TriggeredAlert") && value == 1.0) {
                Alert alert = new Alert(
                    String.valueOf(patient.getPatientId()),
                    "Manual Triggered Alert",
                    timestamp
                );
                triggerAlert(alert);
            }
        }
        checkTrend(systolicBP, patient.getPatientId(), "Systolic Blood Pressure");
        checkTrend(diastolicBP, patient.getPatientId(), "Diastolic Blood Pressure");
   
        if (lowBP && lowOxygen) {
            Alert alert =bloodPressureAlertFactory.createAlert(String.valueOf(patient.getPatientId()),
            "Hypotensive Hypoxemia Alert", now);
            triggerAlert(alert);
        }

        if (!ecgValues.isEmpty()) {
            double avg = ecgValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            for (double val : ecgValues) {
                if (Math.abs(val - avg) > avg * 0.5) { // 50% deviation
                    Alert alert =ecgAlertFactory.createAlert(String.valueOf(patient.getPatientId()),
                    "Abnormal ECG Activity Detected", now);
                    triggerAlert(alert);
                    break;
                } }}
    }

    /**
     * Triggers an alert for the monitoring system. This method can be extended to
     * notify medical staff, log the alert, or perform other actions. The method
     * currently assumes that the alert information is fully formed when passed as
     * an argument.
     *
     * @param alert the alert object containing details about the alert condition
     */
    protected void triggerAlert(Alert alert) {
        // Implementation might involve logging the alert or notifying staff
        triggeredAlerts.add(alert);
        System.out.println("Alert for Patient" + alert.getPatientId() + ":" + alert.getCondition() + "at" + alert.getTimestamp());
    }

     
    /**
     * Retrieves the list of triggered alerts.
     *
     * @return a list of triggered alerts
     */
    public List<Alert> getTriggeredAlerts() {
        return triggeredAlerts;
    }
    /**
     * Checks for trend alerts (3 consecutive increases or decreases by >10 mmHg).
     *
     * @param values list of blood pressure values
     * @param patientId ID of the patient
     * @param label type of pressure (systolic or diastolic)
     */
    private void checkTrend(List<Double> values, int patientId, String label) {
        if (values.size() < 3) return;

        for (int i = 0; i < values.size() - 2; i++) {
            double a = values.get(i);
            double b = values.get(i + 1);
            double c = values.get(i + 2);

            if ((b - a > 10 && c - b > 10) || (a - b > 10 && b - c > 10)) {
                Alert alert =bloodPressureAlertFactory.createAlert(String.valueOf(patientId),
                "Trend Alert: " + label, System.currentTimeMillis());
                triggerAlert(alert);
                break;
            }
        }
    }
}

package org.verapdf.gui;

import org.apache.log4j.Logger;
import org.verapdf.apps.ConfigManager;
import org.verapdf.apps.ProcessType;
import org.verapdf.apps.VeraAppConfig;
import org.verapdf.core.VeraPDFException;
import org.verapdf.gui.tools.GUIConstants;
import org.verapdf.pdfa.validation.profiles.ValidationProfile;
import org.verapdf.pdfa.validation.validators.ValidatorConfig;
import org.verapdf.policy.PolicyChecker;
import org.verapdf.processor.*;
import org.verapdf.processor.reports.BatchSummary;
import org.verapdf.report.HTMLReport;

import javax.swing.*;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.util.EnumSet;
import java.util.List;

/**
 * Validates PDF in a new thread.
 *
 * @author Maksim Bezrukov
 */
class ValidateWorker extends SwingWorker<BatchSummary, Integer> {

    private static final Logger LOGGER = Logger.getLogger(ValidateWorker.class);

    private static final String ERROR_IN_OPEN_STREAMS = "Can't open stream from PDF file or can't open stream to temporary XML report file";
    private static final String ERROR_IN_PROCESSING = "Error during the processing";
    private static final String ERROR_IN_CREATING_TEMP_FILE = "Can't create temporary file for XML report";
    private static final String ERROR_IN_SAVING_REPORT = "Can't serialize xml report";

    private List<File> pdfs;
    private ValidationProfile customProfile;
    private File policy;
    private CheckerPanel parent;
    private ConfigManager configManager;
    private File xmlReport = null;
    private File htmlReport = null;
    private BatchSummary batchSummary = null;

    ValidateWorker(CheckerPanel parent, List<File> pdfs, ConfigManager configManager, ValidationProfile customProfile, File policy) {
        if (pdfs == null) {
            throw new IllegalArgumentException("List of pdf files can not be null");
        }
        this.parent = parent;
        this.pdfs = pdfs;
        this.configManager = configManager;
        this.customProfile = customProfile;
        this.policy = policy;
    }

    @Override
    protected BatchSummary doInBackground() {
        try {
            this.xmlReport = File.createTempFile("veraPDF-tempXMLReport", ".xml");  //$NON-NLS-1$//$NON-NLS-2$
            this.xmlReport.deleteOnExit();
            this.htmlReport = null;
        } catch (IOException e) {
            LOGGER.error(ERROR_IN_CREATING_TEMP_FILE, e);
            this.parent.errorInValidatingOccur(ERROR_IN_CREATING_TEMP_FILE + ": ", e); //$NON-NLS-1$
        }
        try (OutputStream mrrReport = new FileOutputStream(this.xmlReport)) {
            VeraAppConfig veraAppConfig = parent.appConfigFromState();
            ProcessType processType = veraAppConfig.getProcessType();
            EnumSet<TaskType> tasks = processType.getTasks();
            ValidatorConfig validatorConfig = this.configManager.getValidatorConfig();
            ProcessorConfig resultConfig = this.customProfile == null
                    ? ProcessorFactory.fromValues(validatorConfig,
                    this.configManager.getFeaturesConfig(), this.configManager.getFixerConfig(),
                    tasks, veraAppConfig.getFixesFolder())
                    : ProcessorFactory.fromValues(validatorConfig,
                    this.configManager.getFeaturesConfig(), this.configManager.getFixerConfig(),
                    tasks, customProfile, veraAppConfig.getFixesFolder());
            BatchProcessor processor = ProcessorFactory.fileBatchProcessor(resultConfig);
            VeraAppConfig applicationConfig = this.configManager.getApplicationConfig();
            this.batchSummary = processor.process(this.pdfs,
                    ProcessorFactory.getHandler(FormatOption.MRR, applicationConfig.isVerbose(), mrrReport, applicationConfig.getMaxFailsDisplayed(), validatorConfig.isRecordPasses()));

            if ((processType == ProcessType.POLICY || processType == ProcessType.POLICY_FIX) && this.policy != null) {
                applyPolicy();
            }
        } catch (IOException e) {
            LOGGER.error(ERROR_IN_OPEN_STREAMS, e);
            this.parent.errorInValidatingOccur(ERROR_IN_OPEN_STREAMS + ": ", e); //$NON-NLS-1$
        } catch (VeraPDFException e) {
            LOGGER.error(ERROR_IN_PROCESSING, e);
            this.parent.errorInValidatingOccur(ERROR_IN_PROCESSING + ": ", e); //$NON-NLS-1$
        }

        // TODO: change "== 1" to "> 0" when HTML report will work in case of multiply files
        if (this.batchSummary.getValidPdfaCount() + this.batchSummary.getInvalidPdfaCount() > 0) {
            writeHtmlReport();
        }

        return this.batchSummary;
    }

    private void applyPolicy() throws IOException, VeraPDFException {
        File tempMrrFile = this.xmlReport;
        this.xmlReport = File.createTempFile("veraPDF-tempXMLReport", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        this.xmlReport.deleteOnExit();
        File tempPolicyResult = File.createTempFile("policyResult", "veraPDF"); //$NON-NLS-1$ //$NON-NLS-2$
        tempPolicyResult.deleteOnExit();
        try (InputStream mrrIs = new FileInputStream(tempMrrFile);
             OutputStream policyResultOs = new FileOutputStream(tempPolicyResult);
             OutputStream mrrReport = new FileOutputStream(this.xmlReport)) {
            PolicyChecker.applyPolicy(this.policy, mrrIs, policyResultOs);
            PolicyChecker.insertPolicyReport(tempPolicyResult, tempMrrFile, mrrReport);
        }
    }

    @Override
    protected void done() {
        this.parent.validationEnded(this.xmlReport, this.htmlReport);
    }

    private void writeHtmlReport() {
        try {
            this.htmlReport = File.createTempFile("veraPDF-tempHTMLReport", ".html"); //$NON-NLS-1$ //$NON-NLS-2$
            this.htmlReport.deleteOnExit();
            try (InputStream xmlStream = new FileInputStream(this.xmlReport);
                 OutputStream htmlStream = new FileOutputStream(this.htmlReport)) {
                HTMLReport.writeHTMLReport(xmlStream, htmlStream, this.batchSummary,
                        this.configManager.getApplicationConfig().getWikiPath(), true);

            } catch (IOException | TransformerException e) {
                JOptionPane.showMessageDialog(this.parent, GUIConstants.ERROR_IN_SAVING_HTML_REPORT + e.getMessage(),
                        GUIConstants.ERROR, JOptionPane.ERROR_MESSAGE);
                LOGGER.error("Exception saving the HTML report", e);
                this.htmlReport = null;
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this.parent, GUIConstants.ERROR_IN_SAVING_HTML_REPORT + e.getMessage(),
                    GUIConstants.ERROR, JOptionPane.ERROR_MESSAGE);
            LOGGER.error("Exception saving the HTML report", e);
            this.htmlReport = null;
        }
    }
}

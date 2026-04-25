package io.quillloom.infrastructure.postdraft.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "quillloom.postdraft.review.runtime")
public class ReviewAgentRuntimeProperties {

    private Path sessionDirectory = Path.of("target", "review-agent-sessions");
    private Path baselineDirectory = Path.of("target", "review-agent-baselines");
    private boolean cliEnabled;
    private String cliAction = "";
    private String cliProjectId = "";
    private String cliHumanReviewNote = "";
    private long maxWallClockMinutes = 300;
    private int consolePreviewMaxLength = 120;
    private boolean promptDumpEnabled;
    private Path promptDumpDirectory = Path.of("logs", "review-agent-prompts");

    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    public void setSessionDirectory(Path sessionDirectory) {
        this.sessionDirectory = sessionDirectory;
    }

    public Path getBaselineDirectory() {
        return baselineDirectory;
    }

    public void setBaselineDirectory(Path baselineDirectory) {
        this.baselineDirectory = baselineDirectory;
    }

    public boolean isCliEnabled() {
        return cliEnabled;
    }

    public void setCliEnabled(boolean cliEnabled) {
        this.cliEnabled = cliEnabled;
    }

    public String getCliAction() {
        return cliAction;
    }

    public void setCliAction(String cliAction) {
        this.cliAction = cliAction;
    }

    public String getCliProjectId() {
        return cliProjectId;
    }

    public void setCliProjectId(String cliProjectId) {
        this.cliProjectId = cliProjectId;
    }

    public String getCliHumanReviewNote() {
        return cliHumanReviewNote;
    }

    public void setCliHumanReviewNote(String cliHumanReviewNote) {
        this.cliHumanReviewNote = cliHumanReviewNote;
    }

    public long getMaxWallClockMinutes() {
        return maxWallClockMinutes;
    }

    public void setMaxWallClockMinutes(long maxWallClockMinutes) {
        this.maxWallClockMinutes = maxWallClockMinutes;
    }

    public int getConsolePreviewMaxLength() {
        return consolePreviewMaxLength;
    }

    public void setConsolePreviewMaxLength(int consolePreviewMaxLength) {
        this.consolePreviewMaxLength = consolePreviewMaxLength;
    }

    public boolean isPromptDumpEnabled() {
        return promptDumpEnabled;
    }

    public void setPromptDumpEnabled(boolean promptDumpEnabled) {
        this.promptDumpEnabled = promptDumpEnabled;
    }

    public Path getPromptDumpDirectory() {
        return promptDumpDirectory;
    }

    public void setPromptDumpDirectory(Path promptDumpDirectory) {
        this.promptDumpDirectory = promptDumpDirectory;
    }
}

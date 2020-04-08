package com.capitalone.dashboard.collector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Bean to hold settings specific to the Gitlab collector.
 */
@Component
@ConfigurationProperties(prefix = "gitlab")
public class GitlabSettings {


    private String cron;
    private List<String> servers = new ArrayList<>();
    private String projectIds = "";
    private String buildStages;
    private String ignoredBuildStages;
    private List<String> niceNames;
    //eg. DEV, QA, PROD etc
    private List<String> environments = new ArrayList<>();
    private List<String> usernames = new ArrayList<>();
    private String apiKeys;
    private String branchNames;
    private String dockerLocalHostIP; //null if not running in docker on http://localhost
    private int pageSize;
    @Value("${folderDepth:10}")
    private int folderDepth;
    private int firstRunHistoryDays;

    @Value("${gitlab.connectTimeout:20000}")
    private int connectTimeout;

    @Value("${gitlab.readTimeout:20000}")
    private int readTimeout;

    public String getCron() {
        return cron;
    }

    public List<String> getServers() {
        return servers;
    }

    public void setServers(List<String> servers) {
        this.servers = servers;
    }

    public List<String> getApiKeys() {
        return Arrays.asList(apiKeys.split(","));
    }

    public void setApiKeys(String apiKeys) {
        this.apiKeys = apiKeys;
    }

    public List<String> getNiceNames() {
        return niceNames;
    }

    public List<String> getEnvironments() {
        return environments;
    }

    public void setBuildStages(String buildStages) {
        this.buildStages = buildStages;
    }

    public void setIgnoredBuildStages(String ignoredBuildStages) {
        this.ignoredBuildStages = ignoredBuildStages;
    }

    //Docker NATs the real host localhost to 10.0.2.2 when running in docker
	//as localhost is stored in the JSON payload from gitlab we need
	//this hack to fix the addresses
    public String getDockerLocalHostIP() {

    		//we have to do this as spring will return NULL if the value is not set vs and empty string
    	String localHostOverride = "";
    	if (dockerLocalHostIP != null) {
    		localHostOverride = dockerLocalHostIP;
    	}
        return localHostOverride;
    }

    public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
	}

    public int getPageSize() {
		return pageSize;
	}

    public int getFolderDepth() {
        return folderDepth;
    }

    public int getConnectTimeout() { return connectTimeout; }

    public int getReadTimeout() { return readTimeout; }

    public List<String> getProjectIds() {
        return Arrays.asList(projectIds.split(","));
    }

    public void setProjectIds(String projectIds) {
        this.projectIds = projectIds;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getProjectKey(String projectId) {
        return IntStream.range(0, getProjectIds().size())
                .filter(index -> projectId.equals(getProjectIds().get(index)))
                .mapToObj(index -> getApiKeys().get(index))
                .findFirst()
                .orElse("");
    }

    public String getBranchName(String projectId) {
        return IntStream.range(0, getProjectIds().size())
                .filter(index -> projectId.equals(getProjectIds().get(index)))
                .mapToObj(index -> getBranchNames().get(index))
                .findFirst()
                .orElse("");
    }

    List<String> getBuildStages() {
        return Arrays.asList(buildStages.toLowerCase().split(","));
    }

    List<String> getIgnoredBuildStages() {
        return ignoredBuildStages.isEmpty() ?
                Collections.emptyList() : Arrays.asList(ignoredBuildStages.toLowerCase().split(","));
    }

    public List<String> getBranchNames() {
        return Arrays.asList(branchNames.split(","));
    }

    public void setBranchNames(String branchNames) {
        this.branchNames = branchNames;
    }

    public int getFirstRunHistoryDays() {
        return firstRunHistoryDays;
    }

    public void setFirstRunHistoryDays(int firstRunHistoryDays) {
        this.firstRunHistoryDays = firstRunHistoryDays;
    }
}

package com.capitalone.dashboard.model;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension of Collector that stores current build server configuration.
 */
public class GitlabCollector extends Collector {
    private List<String> buildServers = new ArrayList<>();
    private List<String> niceNames = new ArrayList<>();
    private List<String> environments = new ArrayList<>();
    private static final String NICE_NAME = "niceName";
    private static final String JOB_NAME = "options.jobName";


    public List<String> getBuildServers() {
        return buildServers;
    }

    public List<String> getNiceNames() {
        return niceNames;
    }

    public List<String> getEnvironments() {
        return environments;
    }

    //TODO: Remove niceNames, environments?
    public static GitlabCollector prototype(List<String> buildServers, List<String> niceNames,
                                            List<String> environments) {
        GitlabCollector protoType = new GitlabCollector();
        protoType.setName("Gitlab-Build");
        protoType.setCollectorType(CollectorType.Build);
        protoType.setOnline(true);
        protoType.setEnabled(true);
        protoType.getBuildServers().addAll(buildServers);
        if (!CollectionUtils.isEmpty(niceNames)) {
            protoType.getNiceNames().addAll(niceNames);
        }
        if (!CollectionUtils.isEmpty(environments)) {
            protoType.getEnvironments().addAll(environments);
        }
        Map<String, Object> options = new HashMap<>();
        options.put(GitlabProject.INSTANCE_URL,"");
        options.put(GitlabProject.JOB_URL,"");
        options.put(GitlabProject.JOB_NAME,"");

        Map<String, Object> uniqueOptions = new HashMap<>();
        uniqueOptions.put(GitlabProject.JOB_URL,"");
        uniqueOptions.put(GitlabProject.JOB_NAME,"");

        protoType.setAllFields(options);
        protoType.setUniqueFields(uniqueOptions);
        protoType.setSearchFields(Arrays.asList(JOB_NAME,NICE_NAME));
        return protoType;
    }
}

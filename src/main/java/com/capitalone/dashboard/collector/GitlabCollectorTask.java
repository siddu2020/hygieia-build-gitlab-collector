package com.capitalone.dashboard.collector;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.capitalone.dashboard.model.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;

import com.capitalone.dashboard.model.GitlabCollector;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.BuildRepository;
import com.capitalone.dashboard.repository.CollItemConfigHistoryRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.ConfigurationRepository;
import com.capitalone.dashboard.repository.GitlabCollectorRepository;
import com.capitalone.dashboard.repository.GitlabJobRepository;
import com.google.common.collect.Lists;


/**
 * CollectorTask that fetches Build information from Hudson
 */
@Component
public class GitlabCollectorTask extends CollectorTask<GitlabCollector> {
    @SuppressWarnings("PMD.UnusedPrivateField")
    private static final Log LOG = LogFactory.getLog(GitlabCollectorTask.class);

    private final GitlabCollectorRepository gitlabCollectorRepository;
    private final GitlabJobRepository gitlabJobRepository;
    private final BuildRepository buildRepository;
    private final CollItemConfigHistoryRepository configRepository;
    private final GitlabClient gitlabClient;
    private final GitlabSettings gitlabSettings;
    private final ComponentRepository dbComponentRepository;
	private final ConfigurationRepository configurationRepository;

    @Autowired
    public GitlabCollectorTask(TaskScheduler taskScheduler,
                               GitlabCollectorRepository gitlabCollectorRepository,
                               GitlabJobRepository gitlabJobRepository,
                               BuildRepository buildRepository, CollItemConfigHistoryRepository configRepository, GitlabClient gitlabClient,
                               GitlabSettings gitlabSettings,
                               ComponentRepository dbComponentRepository,
                               ConfigurationRepository configurationRepository) {
        super(taskScheduler, "Gitlab-Build");
        this.gitlabCollectorRepository = gitlabCollectorRepository;
        this.gitlabJobRepository = gitlabJobRepository;
        this.buildRepository = buildRepository;
        this.configRepository = configRepository;
        this.gitlabClient = gitlabClient;
        this.gitlabSettings = gitlabSettings;
        this.dbComponentRepository = dbComponentRepository;
		this.configurationRepository = configurationRepository;
    }

    @Override
    public GitlabCollector getCollector() {
    	Configuration config = configurationRepository.findByCollectorName("Gitlab-Build");
        // Only use Admin Page Gitlab server configuration when available
        // otherwise use properties file Gitlab server configuration
        if (config != null ) {
			config.decryptOrEncrptInfo();
			// To clear the username and password from existing run and
			// pick the latest
            gitlabSettings.getUsernames().clear();
            gitlabSettings.getServers().clear();
            gitlabSettings.getApiKeys().clear();
			for (Map<String, String> gitlabServer : config.getInfo()) {
				gitlabSettings.getServers().add(gitlabServer.get("url"));
				gitlabSettings.getUsernames().add(gitlabServer.get("userName"));
				gitlabSettings.getApiKeys().add(gitlabServer.get("password"));
			}
		}
        return GitlabCollector.prototype(gitlabSettings.getServers(), gitlabSettings.getNiceNames(),
                gitlabSettings.getEnvironments());
    }

    @Override
    public BaseCollectorRepository<GitlabCollector> getCollectorRepository() {
        return gitlabCollectorRepository;
    }

    @Override
    public String getCron() {
        return gitlabSettings.getCron();
    }

    @Override
    public void collect(GitlabCollector collector) {
        long start = System.currentTimeMillis();
        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        List<GitlabProject> existingJobs = gitlabJobRepository.findByCollectorIdIn(udId);
        List<GitlabProject> activeJobs = new ArrayList<>();
        List<String> activeServers = new ArrayList<>();
        activeServers.addAll(collector.getBuildServers());

        clean(collector, existingJobs);

        for (String instanceUrl : collector.getBuildServers()) {
            logBanner(instanceUrl);
            try {
                Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> dataByJob = gitlabClient
                        .getInstanceProjects(instanceUrl);
                log("Fetched jobs", start);
                activeJobs.addAll(dataByJob.keySet());
                addNewJobs(dataByJob.keySet(), existingJobs, collector);
                addNewBuilds(enabledJobs(collector, instanceUrl), dataByJob);
                addNewConfigs(enabledJobs(collector, instanceUrl), dataByJob);
                log("Finished", start);
            } catch (RestClientException rce) {
                activeServers.remove(instanceUrl); // since it was a rest exception, we will not delete this job  and wait for
                // rest exceptions to clear up at a later run.
                log("Error getting jobs for: " + instanceUrl, start);
            }
        }
        // Delete jobs that will be no longer collected because servers have moved etc.
        deleteUnwantedJobs(activeJobs, existingJobs, activeServers, collector);
    }

    /**
     * Clean up unused collector items
     *
     * @param collector    the {@link GitlabCollector}
     * @param existingJobs
     */

    private void clean(GitlabCollector collector, List<GitlabProject> existingJobs) {
        Set<ObjectId> uniqueIDs = new HashSet<>();
        for (com.capitalone.dashboard.model.Component comp : dbComponentRepository
                .findAll()) {

            if (CollectionUtils.isEmpty(comp.getCollectorItems())) continue;

            List<CollectorItem> itemList = comp.getCollectorItems().get(CollectorType.Build);

            if (CollectionUtils.isEmpty(itemList)) continue;

            for (CollectorItem ci : itemList) {
                if (collector.getId().equals(ci.getCollectorId())) {
                    uniqueIDs.add(ci.getId());
                }
            }
        }
        List<GitlabProject> stateChangeJobList = new ArrayList<>();
        for (GitlabProject job : existingJobs) {
            if ((job.isEnabled() && !uniqueIDs.contains(job.getId())) ||  // if it was enabled but not on a dashboard
                    (!job.isEnabled() && uniqueIDs.contains(job.getId()))) { // OR it was disabled and now on a dashboard
                job.setEnabled(uniqueIDs.contains(job.getId()));
                stateChangeJobList.add(job);
            }
        }
        if (!CollectionUtils.isEmpty(stateChangeJobList)) {
            gitlabJobRepository.save(stateChangeJobList);
        }
    }

    /**
     * Delete orphaned job collector items
     *
     * @param activeJobs
     * @param existingJobs
     * @param activeServers
     * @param collector
     */
    private void deleteUnwantedJobs(List<GitlabProject> activeJobs, List<GitlabProject> existingJobs, List<String> activeServers, GitlabCollector collector) {

        List<GitlabProject> deleteJobList = new ArrayList<>();
        for (GitlabProject job : existingJobs) {
            if (job.isPushed()) continue; // build servers that push jobs will not be in active servers list by design

            // if we have a collector item for the job in repository but it's build server is not what we collect, remove it.
            if (!collector.getBuildServers().contains(job.getInstanceUrl())) {
                deleteJobList.add(job);
            }

            //if the collector id of the collector item for the job in the repo does not match with the collector ID, delete it.
            if (!Objects.equals(job.getCollectorId(), collector.getId())) {
                deleteJobList.add(job);
            }

            // this is to handle jobs that have been deleted from build servers. Will get 404 if we don't delete them.
            if (activeServers.contains(job.getInstanceUrl()) && !activeJobs.contains(job)) {
                deleteJobList.add(job);
            }

        }
        if (!CollectionUtils.isEmpty(deleteJobList)) {
            gitlabJobRepository.delete(deleteJobList);
        }
    }

    /**
     * Iterates over the enabled build jobs and adds new builds to the database.
     *
     * @param enabledJobs list of enabled {@link GitlabProject}s
     * @param dataByJob maps a {@link GitlabProject} to a map of data with {@link Build}s.
     */
    private void addNewBuilds(List<GitlabProject> enabledJobs,
                              Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> dataByJob) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (GitlabProject job : enabledJobs) {
            if (job.isPushed()) continue;
            // process new builds in the order of their build numbers - this has implication to handling of commits in BuildEventListener

            Map<GitlabClient.jobData, Set<BaseModel>> jobDataSetMap = dataByJob.get(job);
            if (jobDataSetMap == null) {
                continue;
            }
            Set<BaseModel> buildsSet = jobDataSetMap.get(GitlabClient.jobData.BUILD);

            ArrayList<BaseModel> builds = Lists.newArrayList(nullSafe(buildsSet));

            builds.sort(Comparator.comparingInt(b -> Integer.valueOf(((Build) b).getNumber())));
            int counter = 1;
            int totalBuilds = builds.size();
            for (BaseModel buildSummary : builds) {
                Build bSummary = (Build) buildSummary;
                BuildStatus buildStatus = bSummary.getBuildStatus();
                if (buildStatus != null && !buildStatus.equals(BuildStatus.Success)) {
                    LOG.info(String.format("Skipping details for build %d (with status %s) of total %d builds",
                            counter++, buildStatus, totalBuilds));
                    continue;
                }
                if (isNewBuild(job, bSummary)) {
                    LOG.info(String.format("Getting details for new build %d (Number: %s) of total %d builds",
                            counter++, bSummary.getNumber(), totalBuilds));
                    Build build = gitlabClient.getPipelineDetails((bSummary)
                            .getBuildUrl(), job.getInstanceUrl());
                    job.setLastUpdated(System.currentTimeMillis());
                    gitlabJobRepository.save(job);
                    if (build != null) {
                        build.setCollectorItemId(job.getId());
                        buildRepository.save(build);
                        count++;
                    }
                } else {
                    LOG.info(String.format("Skipping details for existing build %d of total %d builds", counter++, totalBuilds));
                }
            }
        }
        log("New builds", start, count);
    }

    private void addNewConfigs(List<GitlabProject> enabledJobs,
                              Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> dataByJob) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (GitlabProject job : enabledJobs) {
            if (job.isPushed()) continue;
            // process new builds in the order of their build numbers - this has implication to handling of commits in BuildEventListener

            Map<GitlabClient.jobData, Set<BaseModel>> jobDataSetMap = dataByJob.get(job);
            if (jobDataSetMap == null) {
                continue;
            }
            Set<BaseModel> configsSet = jobDataSetMap.get(GitlabClient.jobData.CONFIG);

            ArrayList<BaseModel> configs = Lists.newArrayList(nullSafe(configsSet));

            configs.sort(Comparator.comparing(b -> new Date(((CollectorItemConfigHistory) b).getTimestamp())));

            for (BaseModel config : configs) {
                if (config != null && isNewConfig(job, (CollectorItemConfigHistory)config)) {
                    job.setLastUpdated(System.currentTimeMillis());
                    gitlabJobRepository.save(job);
                    ((CollectorItemConfigHistory)config).setCollectorItemId(job.getId());
                    configRepository.save((CollectorItemConfigHistory)config);
                    count++;
                }
            }
        }
        log("New configs", start, count);
    }

    private Set<BaseModel> nullSafe(Set<BaseModel> builds) {
        return builds == null ? new HashSet<>() : builds;
    }

    /**
     * Adds new {@link GitlabProject}s to the database as disabled jobs.
     *
     * @param jobs         list of {@link GitlabProject}s
     * @param existingJobs
     * @param collector    the {@link GitlabCollector}
     */
    private void addNewJobs(Set<GitlabProject> jobs, List<GitlabProject> existingJobs, GitlabCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;

        List<GitlabProject> newJobs = new ArrayList<>();
        for (GitlabProject job : jobs) {
            GitlabProject existing = null;
            if (!CollectionUtils.isEmpty(existingJobs) && (existingJobs.contains(job))) {
                existing = existingJobs.get(existingJobs.indexOf(job));
            }
            String niceName = getNiceName(job, collector);
            String environment = getEnvironment(job, collector);
            if (existing == null) {
                job.setCollectorId(collector.getId());
                job.setEnabled(false); // Do not enable for collection. Will be enabled when added to dashboard
                job.setDescription(job.getJobName());
                if (StringUtils.isNotEmpty(niceName)) {
                    job.setNiceName(niceName);
                }
                if (StringUtils.isNotEmpty(environment)) {
                    job.setEnvironment(environment);
                }
                newJobs.add(job);
                count++;
            } else {
                if (StringUtils.isEmpty(existing.getNiceName()) && StringUtils.isNotEmpty(niceName)) {
                    existing.setNiceName(niceName);
                    gitlabJobRepository.save(existing);
                }
                if (StringUtils.isEmpty(existing.getEnvironment()) && StringUtils.isNotEmpty(environment)) {
                    existing.setEnvironment(environment);
                    gitlabJobRepository.save(existing);
                }
                if (StringUtils.isEmpty(existing.getInstanceUrl())) {
                    existing.setInstanceUrl(job.getInstanceUrl());
                    gitlabJobRepository.save(existing);
                }
            }
        }
        //save all in one shot
        if (!CollectionUtils.isEmpty(newJobs)) {
            gitlabJobRepository.save(newJobs);
        }
        log("New jobs", start, count);
    }

    private String getNiceName(GitlabProject job, GitlabCollector collector) {
        if (CollectionUtils.isEmpty(collector.getBuildServers())) return "";
        List<String> servers = collector.getBuildServers();
        List<String> niceNames = collector.getNiceNames();
        if (CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(job.getInstanceUrl()) && (niceNames.size() > i)) {
                return niceNames.get(i);
            }
        }
        return "";
    }

    private String getEnvironment(GitlabProject job, GitlabCollector collector) {
        if (CollectionUtils.isEmpty(collector.getBuildServers())) return "";
        List<String> servers = collector.getBuildServers();
        List<String> environments = collector.getEnvironments();
        if (CollectionUtils.isEmpty(environments)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(job.getInstanceUrl()) && (environments.size() > i)) {
                return environments.get(i);
            }
        }
        return "";
    }

    private List<GitlabProject> enabledJobs(GitlabCollector collector,
                                            String instanceUrl) {
        return gitlabJobRepository.findEnabledJobs(collector.getId(),
                instanceUrl);
    }

    @SuppressWarnings("unused")
    private GitlabProject getExistingJob(GitlabCollector collector, GitlabProject job) {
        return gitlabJobRepository.findJob(collector.getId(),
                job.getInstanceUrl(), job.getJobName());
    }

    private boolean isNewBuild(GitlabProject job, Build build) {
        return buildRepository.findByCollectorItemIdAndNumber(job.getId(),
                build.getNumber()) == null;
    }

    private boolean isNewConfig(GitlabProject job, CollectorItemConfigHistory config) {
        return configRepository.findByCollectorItemIdAndTimestamp(job.getId(),config.getTimestamp()) == null;
    }
}

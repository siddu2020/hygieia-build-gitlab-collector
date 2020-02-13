package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.BaseModel;
import com.capitalone.dashboard.model.Build;
import com.capitalone.dashboard.model.BuildStatus;
import com.capitalone.dashboard.model.Collector;
import com.capitalone.dashboard.model.CollectorItem;
import com.capitalone.dashboard.model.CollectorType;
import com.capitalone.dashboard.model.Commit;
import com.capitalone.dashboard.model.Dashboard;
import com.capitalone.dashboard.model.EnvironmentStage;
import com.capitalone.dashboard.model.GitlabProject;
import com.capitalone.dashboard.model.Pipeline;
import com.capitalone.dashboard.model.PipelineCommit;
import com.capitalone.dashboard.model.PipelineJobs;
import com.capitalone.dashboard.model.PipelineStage;
import com.capitalone.dashboard.model.RepoBranch;
import com.capitalone.dashboard.repository.CollectorItemRepository;
import com.capitalone.dashboard.repository.CollectorRepository;
import com.capitalone.dashboard.repository.CommitRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.DashboardRepository;
import com.capitalone.dashboard.repository.PipelineRepository;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * GitlabClient implementation that uses RestTemplate and JSONSimple to
 * fetch information from Gitlab instances.
 */
@Component
public class DefaultGitlabClient implements GitlabClient {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultGitlabClient.class);

    private final RestOperations rest;
    private final CommitRepository commitRepository;
    private final CollectorRepository collectorRepository;
    private final CollectorItemRepository collectorItemRepository;
    private final PipelineRepository pipelineRepository;
    private final ComponentRepository componentRepository;
    private final DashboardRepository dashboardRepository;
    private final GitlabSettings settings;

    private static final String GITLAB_API_SUFFIX = "/api/v4/";
    private static final String GITLAB_PROJECT_API_SUFFIX = String.format("%s/%s", GITLAB_API_SUFFIX, "projects");

    @Autowired
    public DefaultGitlabClient(Supplier<RestOperations> restOperationsSupplier,
                               GitlabSettings settings,
                               CommitRepository commitRepository,
                               CollectorRepository collectorRepository, CollectorItemRepository collectorItemRepository,
                               PipelineRepository pipelineRepository, ComponentRepository componentRepository, DashboardRepository dashboardRepository) {
        this.rest = restOperationsSupplier.get();
        this.settings = settings;
        this.commitRepository = commitRepository;
        this.collectorRepository = collectorRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.pipelineRepository = pipelineRepository;
        this.componentRepository = componentRepository;
        this.dashboardRepository = dashboardRepository;
    }

    @Override
    public Map<GitlabProject, Map<jobData, Set<BaseModel>>> getInstanceProjects(String instanceUrl) {
        LOG.debug("Enter getInstanceProjects");
        Map<GitlabProject, Map<jobData, Set<BaseModel>>> result = new LinkedHashMap<>();

        for (String projectId : settings.getProjectIds()) {
            try {
                String url = joinURL(instanceUrl, new String[]{String.format("%s/%s", GITLAB_PROJECT_API_SUFFIX, projectId)});
                final String apiKey = settings.getProjectKey(projectId);
                ResponseEntity<String> responseEntity = makeRestCall(url, apiKey);
                if (responseEntity == null) {
                    break;
                }
                String returnJSON = responseEntity.getBody();
                if (StringUtils.isEmpty(returnJSON)) {
                    break;
                }
                JSONParser parser = new JSONParser();
                try {
                    JSONObject jsonProject = (JSONObject) parser.parse(returnJSON);

                    final String projectName = String.format("%s-%s",
                            "v2", getString(jsonProject, "name"));
                    final String projectURL = getString(jsonProject, "web_url");

                    LOG.debug("Process jobName " + projectName + " jobURL " + projectURL);

                    getProjectDetails(projectName, projectURL, instanceUrl, result, url, apiKey);

                } catch (ParseException e) {
                    LOG.error("Parsing jobs details on instance: " + instanceUrl, e);
                }
            } catch (RestClientException rce) {
                LOG.error("client exception loading jobs details", rce);
                throw rce;
            } catch (URISyntaxException e1) {
                LOG.error("wrong syntax url for loading jobs details", e1);
            }

        }
        return result;
    }

    @SuppressWarnings({"PMD.NPathComplexity", "PMD.ExcessiveMethodLength", "PMD.AvoidBranchingStatementAsLastInLoop", "PMD.EmptyIfStmt"})
    private void getProjectDetails(String projectName, String projectURL, String instanceUrl,
                                   Map<GitlabProject, Map<jobData, Set<BaseModel>>> result, String url, String apiKey) throws URISyntaxException, ParseException {
        LOG.debug("getProjectDetails: projectName " + projectName + " projectURL: " + projectURL);

        Map<jobData, Set<BaseModel>> jobDataMap = new HashMap();

        GitlabProject gitlabProject = new GitlabProject();
        gitlabProject.setInstanceUrl(instanceUrl);
        gitlabProject.setJobName(projectName);
        gitlabProject.setJobUrl(projectURL);
        Set<BaseModel> pipelines = getPipelineDetailsForGitlabProject(url, apiKey);

        jobDataMap.put(jobData.BUILD, pipelines);

        result.put(gitlabProject, jobDataMap);
    }
    private Set<BaseModel> getPipelineDetailsForGitlabProjectPaginated(String projectApiUrl, int pageNum, String apiKey) throws URISyntaxException, ParseException {

        String allPipelinesUrl = String.format("%s/%s", projectApiUrl, "pipelines");
        LOG.info("Fetching pipelines for project {}, page {}", projectApiUrl, pageNum);
        MultiValueMap<String, String> extraQueryParams = new LinkedMultiValueMap<>();
        if (settings.isConsiderOnlyMasterBuilds()) {
            extraQueryParams.put("ref", Arrays.asList("master"));
        }
        ResponseEntity<String> responseEntity = makeRestCall(allPipelinesUrl, pageNum, 100, extraQueryParams, apiKey);
        String returnJSON = responseEntity.getBody();
        if (StringUtils.isEmpty(returnJSON)) {
            return Collections.emptySet();
        }
        JSONParser parser = new JSONParser();
        JSONArray jsonPipelines = (JSONArray) parser.parse(returnJSON);

        if (jsonPipelines.isEmpty()) {
            return Collections.emptySet();
        }

        Set<BaseModel> pipelines = new LinkedHashSet<>();
        for (Object pipeline : jsonPipelines) {
            JSONObject jsonPipeline = (JSONObject) pipeline;
            // A basic Build object. This will be fleshed out later if this is a new Build.
            String pipelineNumber = jsonPipeline.get("id").toString();
            LOG.debug(" pipelineNumber: " + pipelineNumber);
            Build gitlabPipeline = new Build();
            gitlabPipeline.setNumber(pipelineNumber);
            String pipelineURL = String.format("%s/%s", allPipelinesUrl, pipelineNumber); //getString(jsonPipeline, "web_url");
            LOG.debug(" Adding pipeline: " + pipelineURL);
            gitlabPipeline.setBuildUrl(pipelineURL);
            pipelines.add(gitlabPipeline);
        }
        return pipelines;

    }

    private Set<BaseModel> getPipelineDetailsForGitlabProject(String projectApiUrl, String apiKey) throws URISyntaxException, ParseException {
        Set<BaseModel> allPipelines = new LinkedHashSet<>();
        int nextPage = 1;
        while (true) {
            Set<BaseModel> pipelines = getPipelineDetailsForGitlabProjectPaginated(projectApiUrl, nextPage, apiKey);
            if (pipelines.isEmpty()) {
                break;
            }
            allPipelines.addAll(pipelines);
            ++nextPage;
        }
        return allPipelines;
    }

    @Override
    public Build getPipelineDetails(String buildUrl, String instanceUrl) {
        try {
            final String projectId = getProjectIdFromUrl(buildUrl);
            final String apiKey = settings.getProjectKey(projectId);
            ResponseEntity<String> result = makeRestCall(buildUrl, true, apiKey);
            LOG.info(String.format("Getting pipeline details: %s", buildUrl));
            String resultJSON = result.getBody();
            if (StringUtils.isEmpty(resultJSON)) {
                LOG.error("Error getting build details for. URL=" + buildUrl);
                return null;
            }
            PipelineJobs jobsForPipeline = getJobsForPipeline(buildUrl, apiKey);
            JSONParser parser = new JSONParser();
            try {
                JSONObject buildJson = (JSONObject) parser.parse(resultJSON);
                Boolean building = "running".equalsIgnoreCase(getString(buildJson, "status"));
                Boolean cancelled = "canceled".equalsIgnoreCase(getString(buildJson, "status"));

                if (!building && !cancelled) {
                    Build build = new Build();
                    build.setNumber(buildJson.get("id").toString());
                    build.setBuildUrl(buildUrl);
                    build.setTimestamp(jobsForPipeline.getEarliestStartTime());
                    build.setStartTime(jobsForPipeline.getEarliestStartTime());
                    build.setDuration(jobsForPipeline.getRelevantJobTime());
                    build.setEndTime(jobsForPipeline.getLastEndTime());
                    build.setBuildStatus(getBuildStatus(buildJson));
                    Iterable<String> commitIds = jobsForPipeline.getCommitIds();
                    List<Commit> commits = new ArrayList<>();
                    if (commitIds != null) {
                        commitIds.forEach(commitId -> {
                            List<Commit> revisionNumbers = commitRepository.findByScmRevisionNumber(commitId).stream()
                                    .filter(c -> c.getScmParentRevisionNumbers().size() > 1) /* Extract only merge commits */
                                    .collect(Collectors.toList());
                            if (revisionNumbers != null && !revisionNumbers.isEmpty()) {
                                commits.addAll(revisionNumbers);
                            }
                        });
                    }
                    build.setSourceChangeSet(Collections.unmodifiableList(commits));
                    processPipelineCommits(Collections.unmodifiableList(commits), jobsForPipeline.getEarliestStartTime());
                    build.setStartedBy(getString(((JSONObject) buildJson.get("user")), "name"));
                    build.getCodeRepos().add(
                            new RepoBranch("todo-url", getString(buildJson, "ref"), RepoBranch.RepoType.GIT));
                    return build;
                }

            } catch (ParseException e) {
                LOG.error("Parsing build: " + buildUrl, e);
            }
        } catch (RestClientException rce) {
            LOG.error("Client exception loading build details: " + rce.getMessage() + ". URL =" + buildUrl);
        } catch (RuntimeException re) {
            LOG.error("Unknown error in getting build details. URL=" + buildUrl, re);
        }
        return null;
    }

    private String getProjectIdFromUrl(String buildUrl) {
        //TODO Null checks
        final String s = buildUrl.split("projects/")[1];
        final String projectId = s.substring(0, s.indexOf("/"));
        return projectId;
    }

    /**
     * Finds or creates a pipeline for a dashboard collectoritem
     *
     * @param collectorItem
     * @return
     */
    protected Pipeline getOrCreatePipeline(CollectorItem collectorItem) {
        Pipeline pipeline = pipelineRepository.findByCollectorItemId(collectorItem.getId());
        if (pipeline == null) {
            pipeline = new Pipeline();
            pipeline.setCollectorItemId(collectorItem.getId());
        }
        return pipeline;
    }

    private void processPipelineCommits(List<Commit> commits, long timestamp) {
        if (commits.size() > 0) {
            List<Dashboard> allDashboardsForCommit = findAllDashboardsForCommit(commits.get(0));

            String environmentName = PipelineStage.BUILD.getName();
            List<Collector> collectorList = collectorRepository.findByCollectorType(CollectorType.Product);
            List<CollectorItem> collectorItemList = collectorItemRepository.findByCollectorIdIn(collectorList.stream().map(BaseModel::getId).collect(Collectors.toList()));


            for (CollectorItem collectorItem : collectorItemList) {
                List<String> dashBoardIds = allDashboardsForCommit.stream().map(d -> d.getId().toString()).collect(Collectors.toList());
                boolean dashboardId = dashBoardIds.contains(collectorItem.getOptions().get("dashboardId").toString());
                if (dashboardId) {
                    Pipeline pipeline = getOrCreatePipeline(collectorItem);
                    Map<String, EnvironmentStage> environmentStageMap = pipeline.getEnvironmentStageMap();
                    if (environmentStageMap.get(environmentName) == null) {
                        environmentStageMap.put(environmentName, new EnvironmentStage());
                    }

                    EnvironmentStage environmentStage = environmentStageMap.get(environmentName);
                    if (environmentStage.getCommits() == null) {
                        environmentStage.setCommits(new HashSet<>());
                    }
                    environmentStage.getCommits().addAll(commits.stream()
                            .map(commit -> {
                                //commit.setTimestamp(timestamp); <--- Not sure if we should do this.
                                // IMO, the SCM timestamp should be unaltered.
                                return new PipelineCommit(commit, timestamp);
                            }).collect(Collectors.toSet()));
                    pipelineRepository.save(pipeline);
                }
            }
        }
    }

    private List<Dashboard> findAllDashboardsForCommit(Commit commit) {
        if (commit.getCollectorItemId() == null) return new ArrayList<>();
        CollectorItem commitCollectorItem = collectorItemRepository.findOne(commit.getCollectorItemId());
        List<com.capitalone.dashboard.model.Component> components = componentRepository.findBySCMCollectorItemId(commitCollectorItem.getId());
        List<ObjectId> componentIds = components.stream().map(BaseModel::getId).collect(Collectors.toList());
        return dashboardRepository.findByApplicationComponentIdsIn(componentIds);
    }

    private PipelineJobs getJobsForPipeline(String buildUrl, String apiKey) {
        PipelineJobs pipelineJobs = new PipelineJobs();
        try {
            ResponseEntity<String> result = makeRestCall(buildUrl + "/jobs", true, apiKey);
            String resultJSON = result.getBody();
            if (StringUtils.isEmpty(resultJSON)) {
                LOG.error("Error getting build details for. URL=" + buildUrl);
                return null;
            }
            JSONParser parser = new JSONParser();
            try {
                JSONArray jobsJson = (JSONArray) parser.parse(resultJSON);
                for (int i = 0; i < jobsJson.size(); i++) {
                    pipelineJobs.addJob((JSONObject) jobsJson.get(i));
                }
            } catch (ParseException e) {
                LOG.error("Parsing build: " + buildUrl, e);
            }
        } catch (RestClientException rce) {
            LOG.error("Client exception loading build details: " + rce.getMessage() + ". URL =" + buildUrl);
        } catch (RuntimeException re) {
            LOG.error("Unknown error in getting build details. URL=" + buildUrl, re);
        }
        return pipelineJobs;
    }


    private String getString(JSONObject json, String key) {
        return (String) json.get(key);
    }

    private BuildStatus getBuildStatus(JSONObject buildJson) {
        String status = buildJson.get("status").toString();
        switch (status) {
            case "success":
                return BuildStatus.Success;
            case "failed":
                return BuildStatus.Failure;
            case "canceled":
                return BuildStatus.Aborted;
            default:
                return BuildStatus.Unknown;
        }
    }

    private ResponseEntity<String> makeRestCall(String url, String apiKey) {
        return makeRestCall(url, false, apiKey);
    }
    @SuppressWarnings("PMD")
    private ResponseEntity<String> makeRestCall(String sUrl, boolean maximizePageSize, String apiKey) {
        return makeRestCall(sUrl, 1, maximizePageSize ? 100: 20, new LinkedMultiValueMap<>(), apiKey);
    }

    private ResponseEntity<String> makeRestCall(String sUrl, int pageNum, int pageSize, MultiValueMap<String, String> extraQueryParams, String apiKey) {
        LOG.debug("Enter makeRestCall " + sUrl);
        UriComponentsBuilder thisUri =
                UriComponentsBuilder.fromHttpUrl(sUrl)
                        .queryParam("per_page", pageSize)
                        .queryParam("page", pageNum)
                        .queryParams(extraQueryParams);

        return rest.exchange(thisUri.toUriString(), HttpMethod.GET,
                new HttpEntity<>(createHeaders(apiKey)),
                String.class);

    }

    protected HttpHeaders createHeaders(final String apiToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("PRIVATE-TOKEN", apiToken);
        return headers;
    }

    // join a base url to another path or paths - this will handle trailing or non-trailing /'s
    public static String joinURL(String base, String[] paths) {
        StringBuilder result = new StringBuilder(base);
        Arrays.stream(paths).map(path -> path.replaceFirst("^(\\/)+", "")).forEach(p -> {
            if (result.lastIndexOf("/") != result.length() - 1) {
                result.append('/');
            }
            result.append(p);
        });
        return result.toString();
    }
}

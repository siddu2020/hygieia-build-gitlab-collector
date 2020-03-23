package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
                               CollectorRepository collectorRepository, @Qualifier("collectorItemRepository") CollectorItemRepository collectorItemRepository,
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

                    getProjectDetails(projectName, projectURL, instanceUrl, result, url, projectId);

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

    private Commit getCommit(String commitId, String instanceUrl, String projectId) {
        String url = joinURL(instanceUrl, new String[]{String.format("%s/%s", GITLAB_PROJECT_API_SUFFIX, projectId), "repository/commits", commitId});
        final String apiKey = settings.getProjectKey(projectId);
        ResponseEntity<GitLabCommit> response = makeCommitRestCall(url, apiKey);

        GitLabCommit gitlabCommit = response.getBody();

        if (gitlabCommit == null) {
            return null;
        }

        long timestamp = new DateTime(gitlabCommit.getCreatedAt()).getMillis();
        int parentSize = CollectionUtils.isNotEmpty(gitlabCommit.getParentIds()) ? gitlabCommit.getParentIds().size() : 0;
        CommitType commitType = parentSize > 1 ? CommitType.Merge : CommitType.New;

        if(gitlabCommit.getLastPipeline() == null) {
            return null;
        }

        String web_url = gitlabCommit.getLastPipeline().getWeb_url();
        String repo_url = web_url.split("/pipelines")[0];
        Commit commit = new Commit();
        commit.setTimestamp(System.currentTimeMillis());
        commit.setScmUrl(repo_url);
        commit.setScmBranch(gitlabCommit.getLastPipeline().getRef());
        commit.setScmRevisionNumber(gitlabCommit.getId());
        commit.setScmAuthor(gitlabCommit.getAuthorName());
        commit.setScmCommitLog(gitlabCommit.getMessage());
        commit.setScmCommitTimestamp(timestamp);
        commit.setNumberOfChanges(1);
        commit.setScmParentRevisionNumbers(gitlabCommit.getParentIds());
        commit.setType(commitType);
        return commit;


    }

    @SuppressWarnings({"PMD.NPathComplexity", "PMD.ExcessiveMethodLength", "PMD.AvoidBranchingStatementAsLastInLoop", "PMD.EmptyIfStmt"})
    private void getProjectDetails(String projectName, String projectURL, String instanceUrl,
                                   Map<GitlabProject, Map<jobData, Set<BaseModel>>> result, String url, String projectId) throws URISyntaxException, ParseException {
        LOG.debug("getProjectDetails: projectName " + projectName + " projectURL: " + projectURL);

        Map<jobData, Set<BaseModel>> jobDataMap = new HashMap();

        GitlabProject gitlabProject = new GitlabProject();
        gitlabProject.setInstanceUrl(instanceUrl);
        gitlabProject.setJobName(projectName);
        gitlabProject.setJobUrl(projectURL);
        gitlabProject.getOptions().put("projectId", projectId);

        final String apiKey = settings.getProjectKey(projectId);
        final String branchName = settings.getBranchName(projectId);
        Set<BaseModel> pipelines = getPipelineDetailsForGitlabProject(url, apiKey, branchName);

        jobDataMap.put(jobData.BUILD, pipelines);

        result.put(gitlabProject, jobDataMap);
    }

    private Set<BaseModel> getPipelineDetailsForGitlabProjectPaginated(String projectApiUrl, int pageNum, String apiKey, String branchName) throws URISyntaxException, ParseException {

        String allPipelinesUrl = String.format("%s/%s", projectApiUrl, "pipelines");
        LOG.info("Fetching pipelines for project {}, page {}", projectApiUrl, pageNum);
        MultiValueMap<String, String> extraQueryParams = new LinkedMultiValueMap<>();

        extraQueryParams.put("ref", Collections.singletonList(branchName));
        extraQueryParams.put("updated_after", Collections.singletonList(getBuildThresholdTime()));

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

    private Set<BaseModel> getPipelineDetailsForGitlabProject(String projectApiUrl, String apiKey, String branchName) throws URISyntaxException, ParseException {
        Set<BaseModel> allPipelines = new LinkedHashSet<>();
        int nextPage = 1;
        while (true) {
            Set<BaseModel> pipelines = getPipelineDetailsForGitlabProjectPaginated(projectApiUrl, nextPage, apiKey, branchName);
            if (pipelines.isEmpty()) {
                break;
            }
            allPipelines.addAll(pipelines);
            ++nextPage;
        }
        return allPipelines;
    }

    @Override
    public Build getPipelineDetails(String buildUrl, String instanceUrl, String gitProjectId, ObjectId collectorId) {
        try {
            final String apiKey = settings.getProjectKey(gitProjectId);
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

                if (isBuildCompleted(buildJson) && jobsForPipeline != null) {
                    Build build = getBuild(buildUrl, jobsForPipeline, buildJson);
                    Iterable<String> commitIds = jobsForPipeline.getCommitIds();
                    List<Commit> commits = new ArrayList<>();
                    if (commitIds != null) {
                        commitIds.forEach(commitId -> {
                            List<Commit> matchedCommits = commitRepository.findByScmRevisionNumber(commitId);
                            Commit newCommit;
                            if (matchedCommits != null && matchedCommits.size() > 0) {
                                newCommit = matchedCommits.get(0);
                            } else {
                                newCommit = getCommit(commitId, instanceUrl, gitProjectId);

                            }

                            List<String> parentRevisionNumbers = newCommit != null ? newCommit.getScmParentRevisionNumbers() : null;
                            /* Extract only merge comm  its */
                            if (parentRevisionNumbers != null && !parentRevisionNumbers.isEmpty() && parentRevisionNumbers.size() > 1) {
                                commits.add(newCommit);
                            }


                        });
                    }
                    build.setSourceChangeSet(Collections.unmodifiableList(commits));
                    processPipelineCommits(Collections.unmodifiableList(commits), jobsForPipeline.getEarliestStartTime(settings.getBuildStages()), collectorId, gitProjectId);

                    if(buildJson.containsKey("user") && buildJson.get("user") !=null){
                        build.setStartedBy(getString(((JSONObject) buildJson.get("user")), "name"));
                    }

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

    private String getBuildThresholdTime() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
                .withZone(ZoneOffset.UTC).format
                        (Instant.now().minus(settings.getFirstRunHistoryDays(), ChronoUnit.DAYS));
    }

    private Boolean isBuildCompleted(JSONObject buildJson) {
        boolean building = "running".equalsIgnoreCase(getString(buildJson, "status"));
        boolean cancelled = "canceled".equalsIgnoreCase(getString(buildJson, "status"));
        return !building && !cancelled;
    }

    private Build getBuild(String buildUrl, PipelineJobs jobsForPipeline, JSONObject buildJson) {
        Build build = new Build();
        build.setNumber(buildJson.get("id").toString());
        build.setBuildUrl(buildUrl);
        build.setTimestamp(jobsForPipeline.getEarliestStartTime(settings.getBuildStages()));
        build.setStartTime(jobsForPipeline.getEarliestStartTime(settings.getBuildStages()));
        build.setDuration(jobsForPipeline.getRelevantJobTime(settings.getBuildStages()));
        build.setEndTime(jobsForPipeline.getLastEndTime(settings.getBuildStages()));
        build.setBuildStatus(jobsForPipeline.getBuildStatus(settings.getBuildStages()));
        return build;
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

    private void processPipelineCommits(List<Commit> commits, long timestamp, ObjectId collectorId, String gitProjectId) {
        if (commits.size() <= 0) {
            return;
        }
        List<Dashboard> allDashboardsForCommit = findAllDashboardsForCollectorId(collectorId, gitProjectId);
        List<String> dashBoardIds = allDashboardsForCommit.stream().map(d -> d.getId().toString()).collect(Collectors.toList());

        String environmentName = PipelineStage.BUILD.getName();
        String commitStageName = PipelineStage.COMMIT.getName();
        List<Collector> collectorList = collectorRepository.findByCollectorType(CollectorType.Product);
        List<CollectorItem> collectorItemList = collectorItemRepository.findByCollectorIdIn(collectorList.stream().map(BaseModel::getId).collect(Collectors.toList()));


        for (CollectorItem collectorItem : collectorItemList) {
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

                HashSet<PipelineCommit> pipelineCommits = new HashSet<>();
                EnvironmentStage commitStage = environmentStageMap.get(commitStageName);

                if(commitStage != null && commits.size() > 0) {
                    for (Commit commit : commits) {
                        List<PipelineCommit> cs = commitStage.getCommits().stream()
                                .filter(c -> c.getScmParentRevisionNumbers().size() > 1 && c.getTimestamp() < commit.getTimestamp()).collect(Collectors.toList());
                        pipelineCommits.addAll(cs);
                    }
                }
                environmentStage.getCommits().addAll(commits.stream()
                        .map(commit -> {
                            //commit.setTimestamp(timestamp); <--- Not sure if we should do this.
                            // IMO, the SCM timestamp should be unaltered.
                            return new PipelineCommit(commit, timestamp);
                        }).collect(Collectors.toSet()));
                environmentStage.getCommits().addAll(pipelineCommits);
                pipelineRepository.save(pipeline);
            }
        }
    }

    private List<Dashboard> findAllDashboardsForCollectorId(ObjectId collectorId, String gitProjectId) {
        List<CollectorItem> collectorItems = collectorItemRepository.findByCollectorIdIn(Collections.singletonList(collectorId));
        if (collectorItems == null || collectorItems.size() == 0) {
            return Collections.emptyList();
        }

        Optional<CollectorItem> collectorItemOptional =
                collectorItems.stream().filter(item ->
                        gitProjectId.equals(item.getOptions().get("projectId"))).findFirst();
        if (!collectorItemOptional.isPresent()) {
            return Collections.emptyList();
        }
        CollectorItem collectorItem = collectorItemOptional.get();
        List<com.capitalone.dashboard.model.Component> components = componentRepository
                .findByBuildCollectorItemId(collectorItem.getId());
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

    private ResponseEntity<GitLabCommit> makeCommitRestCall(String url, String apiKey) {
        return rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(createHeaders(apiKey)), GitLabCommit.class);
    }

    @SuppressWarnings("PMD")
    private ResponseEntity<String> makeRestCall(String sUrl, boolean maximizePageSize, String apiKey) {
        return makeRestCall(sUrl, 1, maximizePageSize ? 100: 20, new LinkedMultiValueMap<>(), apiKey);
    }

    private ResponseEntity<String> makeRestCall(String sUrl, int pageNum, int pageSize, MultiValueMap<String, String> extraQueryParams, String apiKey) {
        LOG.debug("Enter makeRestCall " + sUrl);
        UriComponentsBuilder thisuri =
                UriComponentsBuilder.fromHttpUrl(sUrl)
                        .queryParam("per_page", pageSize)
                        .queryParam("page", pageNum)
                        .queryParams(extraQueryParams);

        return rest.exchange(thisuri.toUriString(), HttpMethod.GET,
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

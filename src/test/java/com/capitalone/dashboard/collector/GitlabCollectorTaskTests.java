//package com.capitalone.dashboard.collector;
//
//import com.capitalone.dashboard.model.*;
//import com.capitalone.dashboard.model.GitlabCollector;
//import com.capitalone.dashboard.repository.BuildRepository;
//import com.capitalone.dashboard.repository.ComponentRepository;
//import com.capitalone.dashboard.repository.GitlabCollectorRepository;
//import com.capitalone.dashboard.repository.GitlabJobRepository;
//import com.google.common.collect.Sets;
//import org.bson.types.ObjectId;
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.runners.MockitoJUnitRunner;
//import org.springframework.scheduling.TaskScheduler;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//
//import static org.mockito.Matchers.anyListOf;
//import static org.mockito.Mockito.never;
//import static org.mockito.Mockito.times;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.verifyNoMoreInteractions;
//import static org.mockito.Mockito.verifyZeroInteractions;
//import static org.mockito.Mockito.when;
//
//@RunWith(MockitoJUnitRunner.class)
//public class GitlabCollectorTaskTests {
//
//    @Mock
//    private TaskScheduler taskScheduler;
//    @Mock
//    private GitlabCollectorRepository gitlabCollectorRepository;
//    @Mock
//    private GitlabJobRepository gitlabJobRepository;
//    @Mock
//    private BuildRepository buildRepository;
//    @Mock
//    private GitlabClient gitlabClient;
//    @Mock
//    private GitlabSettings gitlabSettings;
//    @Mock
//    private ComponentRepository dbComponentRepository;
//
//    @InjectMocks
//    private GitlabCollectorTask task;
//
//    private static final String SERVER1 = "server1";
//    private static final String NICENAME1 = "niceName1";
//    private static final String ENVIONMENT1 = "DEV";
//
//    @Test
//    public void collect_noBuildServers_nothingAdded() {
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(new GitlabCollector());
//        verifyZeroInteractions(gitlabClient, buildRepository);
//    }
//
//    @Test
//    public void collect_noJobsOnServer_nothingAdded() {
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(new HashMap<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>>());
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collectorWithOneServer());
//
//        verify(gitlabClient).getInstanceProjects(SERVER1);
//        verifyNoMoreInteractions(gitlabClient, buildRepository);
//    }
//
//    @Test
//    public void collect_twoJobs_jobsAdded() {
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(twoJobsWithTwoBuilds(SERVER1, NICENAME1));
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        List<GitlabProject> gitlabJobs = new ArrayList<>();
//        GitlabProject gitlabJob = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        gitlabJobs.add(gitlabJob);
//        when(gitlabJobRepository.findEnabledJobs(null, "server1")).thenReturn(gitlabJobs);
//        task.collect(collectorWithOneServer());
//        verify(gitlabJobRepository, times(1)).save(anyListOf(GitlabProject.class));
//    }
//
//    @Test
//    public void collect_twoJobs_jobsAdded_random_order() {
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(twoJobsWithTwoBuilds(SERVER1, NICENAME1));
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        List<GitlabProject> gitlabJobs = new ArrayList<>();
//        GitlabProject gitlabJob = gitlabJob("2", SERVER1, "JOB2_URL", NICENAME1);
//        gitlabJobs.add(gitlabJob);
//        when(gitlabJobRepository.findEnabledJobs(null, "server1")).thenReturn(gitlabJobs);
//        task.collect(collectorWithOneServer());
//        verify(gitlabJobRepository, times(1)).save(anyListOf(GitlabProject.class));
//    }
//
//
//    @Test
//    public void collect_oneJob_exists_notAdded() {
//        GitlabCollector collector = collectorWithOneServer();
//        GitlabProject job = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job));
//        when(gitlabJobRepository.findJob(collector.getId(), SERVER1, job.getJobName()))
//                .thenReturn(job);
//        when(dbComponentRepository.findAll()).thenReturn(components());
//
//        task.collect(collector);
//
//        verify(gitlabJobRepository, never()).save(job);
//    }
//
//
//    @Test
//    public void delete_job() {
//        GitlabCollector collector = collectorWithOneServer();
//        collector.setId(ObjectId.get());
//        GitlabProject job1 = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        job1.setCollectorId(collector.getId());
//        GitlabProject job2 = gitlabJob("2", SERVER1, "JOB2_URL", NICENAME1);
//        job2.setCollectorId(collector.getId());
//        List<GitlabProject> jobs = new ArrayList<>();
//        jobs.add(job1);
//        jobs.add(job2);
//        Set<ObjectId> udId = new HashSet<>();
//        udId.add(collector.getId());
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job1));
//        when(gitlabJobRepository.findByCollectorIdIn(udId)).thenReturn(jobs);
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collector);
//        List<GitlabProject> delete = new ArrayList<>();
//        delete.add(job2);
//        verify(gitlabJobRepository, times(1)).delete(delete);
//    }
//
//    @Test
//    public void delete_never_job() {
//        GitlabCollector collector = collectorWithOneServer();
//        collector.setId(ObjectId.get());
//        GitlabProject job1 = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        job1.setCollectorId(collector.getId());
//        List<GitlabProject> jobs = new ArrayList<>();
//        jobs.add(job1);
//        Set<ObjectId> udId = new HashSet<>();
//        udId.add(collector.getId());
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job1));
//        when(gitlabJobRepository.findByCollectorIdIn(udId)).thenReturn(jobs);
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collector);
//        verify(gitlabJobRepository, never()).delete(anyListOf(GitlabProject.class));
//    }
//
//    @Test
//    public void collect_jobNotEnabled_buildNotAdded() {
//        GitlabCollector collector = collectorWithOneServer();
//        GitlabProject job = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        Build build = build("1", "JOB1_1_URL");
//
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job, build));
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collector);
//
//        verify(buildRepository, never()).save(build);
//    }
//
//    @Test
//    public void collect_jobEnabled_buildExists_buildNotAdded() {
//        GitlabCollector collector = collectorWithOneServer();
//        GitlabProject job = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        Build build = build("1", "JOB1_1_URL");
//
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job, build));
//        when(gitlabJobRepository.findEnabledJobs(collector.getId(), SERVER1))
//                .thenReturn(Arrays.asList(job));
//        when(buildRepository.findByCollectorItemIdAndNumber(job.getId(), build.getNumber())).thenReturn(build);
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collector);
//
//        verify(buildRepository, never()).save(build);
//    }
//
//    @Test
//    public void collect_jobEnabled_newBuild_buildAdded() {
//        GitlabCollector collector = collectorWithOneServer();
//        GitlabProject job = gitlabJob("1", SERVER1, "JOB1_URL", NICENAME1);
//        Build build = build("1", "JOB1_1_URL");
//
//        when(gitlabClient.getInstanceProjects(SERVER1)).thenReturn(oneJobWithBuilds(job, build));
//        when(gitlabJobRepository.findEnabledJobs(collector.getId(), SERVER1))
//                .thenReturn(Arrays.asList(job));
//        when(buildRepository.findByCollectorItemIdAndNumber(job.getId(), build.getNumber())).thenReturn(null);
//        when(gitlabClient.getPipelineDetails(build.getBuildUrl(), job.getInstanceUrl())).thenReturn(build);
//        when(dbComponentRepository.findAll()).thenReturn(components());
//        task.collect(collector);
//
//        verify(buildRepository, times(1)).save(build);
//    }
//
//    private GitlabCollector collectorWithOneServer() {
//        return GitlabCollector.prototype(Arrays.asList(SERVER1), Arrays.asList(NICENAME1), Arrays.asList(ENVIONMENT1));
//    }
//
//    private Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> oneJobWithBuilds(GitlabProject job, Build... builds) {
//        Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> jobs = new HashMap<>();
//
//        Map<GitlabClient.jobData, Set<BaseModel>> buildsMap = new HashMap<>();
//        buildsMap.put( GitlabClient.jobData.BUILD, Sets.newHashSet(builds) );
//
//        jobs.put(job, buildsMap);
//        return jobs;
//    }
//
//    private Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> twoJobsWithTwoBuilds(String server, String niceName) {
//        Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> jobs = new HashMap<>();
//
//        Map<GitlabClient.jobData, Set<BaseModel>> buildsMap = new HashMap<>();
//        buildsMap.put(GitlabClient.jobData.BUILD, Sets.newHashSet(build("1", "JOB1_1_URL"), build("1", "JOB1_2_URL")));
//        buildsMap.put(GitlabClient.jobData.BUILD, Sets.newHashSet(build("2", "JOB2_1_URL"), build("2", "JOB2_2_URL")));
//
//        jobs.put(gitlabJob("1", server, "JOB1_URL", niceName), buildsMap );
//        jobs.put(gitlabJob("2", server, "JOB2_URL", niceName), buildsMap );
//        return jobs;
//    }
//
//    private Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> twoJobsWithTwoBuildsRandom(String server, String niceName) {
//        Map<GitlabProject, Map<GitlabClient.jobData, Set<BaseModel>>> jobs = new HashMap<>();
//
//        Map<GitlabClient.jobData, Set<BaseModel>> buildsMap = new HashMap<>();
//        buildsMap.put(GitlabClient.jobData.BUILD, Sets.newHashSet(build("2", "JOB2_1_URL"), build("2", "JOB2_2_URL")));
//        buildsMap.put(GitlabClient.jobData.BUILD, Sets.newHashSet(build("1", "JOB1_1_URL"), build("1", "JOB1_2_URL")));
//
//        jobs.put(gitlabJob("2", server, "JOB2_URL", niceName), buildsMap );
//        jobs.put(gitlabJob("1", server, "JOB1_URL", niceName), buildsMap );
//        return jobs;
//    }
//
//    private GitlabProject gitlabJob(String jobName, String instanceUrl, String jobUrl, String niceName) {
//        GitlabProject job = new GitlabProject();
//        job.setJobName(jobName);
//        job.setInstanceUrl(instanceUrl);
//        job.setJobUrl(jobUrl);
//        job.setNiceName(niceName);
//        return job;
//    }
//
//    private Build build(String number, String url) {
//        Build build = new Build();
//        build.setNumber(number);
//        build.setBuildUrl(url);
//        return build;
//    }
//
//    private ArrayList<com.capitalone.dashboard.model.Component> components() {
//        ArrayList<com.capitalone.dashboard.model.Component> cArray = new ArrayList<com.capitalone.dashboard.model.Component>();
//        com.capitalone.dashboard.model.Component c = new Component();
//        c.setId(new ObjectId());
//        c.setName("COMPONENT1");
//        c.setOwner("JOHN");
//        cArray.add(c);
//        return cArray;
//    }
//}

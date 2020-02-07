package com.capitalone.dashboard.model;

/**
 * CollectorItem extension to store the instance, build job and build url.
 */
public class GitlabProject extends JobCollectorItem {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
        	return true;
        }
        if (o == null || getClass() != o.getClass()) {
        	return false;
        }

        GitlabProject gitlabProject = (GitlabProject) o;

        return getJobUrl().equals(gitlabProject.getJobUrl()) && getJobName().equals(gitlabProject.getJobName());
    }

    @Override
    public int hashCode() {
        int result = getJobUrl().hashCode();
        result = 31 * result + getJobName().hashCode();
        return result;
    }
}

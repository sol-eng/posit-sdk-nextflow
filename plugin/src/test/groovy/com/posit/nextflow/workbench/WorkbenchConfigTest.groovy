package com.posit.nextflow.workbench

import spock.lang.Specification

class WorkbenchConfigTest extends Specification {

    def 'should leave the optional settings unset'() {
        when:
        final config = new WorkbenchConfig([:])

        then:
        config.cluster == null
        config.launcherCli == null
        config.resourceProfile == null
    }

    def 'should read all settings from the workbench scope'() {
        when:
        final config = new WorkbenchConfig([cluster: 'k8s', launcherCli: '/opt/wb', resourceProfile: 'small'])

        then:
        config.cluster == 'k8s'
        config.launcherCli == '/opt/wb'
        config.resourceProfile == 'small'
    }
}

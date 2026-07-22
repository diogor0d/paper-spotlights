[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')]
    [string] $Tag
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

Push-Location -LiteralPath $repositoryRoot
try {
    $projectVersionOutput = & .\mvnw.cmd help:evaluate '-Dexpression=project.version' -q -DforceStdout
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not read the Maven project version.'
    }

    $projectVersion = $projectVersionOutput |
        Where-Object { $_ -match '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' } |
        Select-Object -Last 1
    if (-not $projectVersion) {
        throw 'Maven did not return a stable MAJOR.MINOR.PATCH project version.'
    }

    $expectedTag = "v$projectVersion"
    if ($Tag -cne $expectedTag) {
        throw "Tag $Tag does not match pom.xml version $projectVersion; expected $expectedTag."
    }

    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') {
        throw "Release preflight must run on main; current branch is '$branch'."
    }

    $worktreeStatus = & git status --porcelain
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect the Git worktree.'
    }
    if ($worktreeStatus) {
        throw 'Commit and push all release changes before running this preflight.'
    }

    $headCommit = (& git rev-parse HEAD).Trim()
    $pushedMainCommit = (& git rev-parse refs/remotes/origin/main).Trim()
    if ($LASTEXITCODE -ne 0 -or $headCommit -ne $pushedMainCommit) {
        throw 'Local main does not match origin/main. Push the release commit before tagging it.'
    }

    $existingTag = & git tag --list $Tag
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect local Git tags.'
    }
    if ($existingTag) {
        throw "Tag $Tag already exists locally. Release tags must not be moved or reused."
    }

    $remoteTag = & git ls-remote --tags origin "refs/tags/$Tag"
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect remote Git tags.'
    }
    if ($remoteTag) {
        throw "Tag $Tag already exists on origin. Release tags must not be moved or reused."
    }

    & .\mvnw.cmd clean verify
    if ($LASTEXITCODE -ne 0) {
        throw 'Release verification failed; do not create the tag.'
    }

    Write-Host ''
    Write-Host "Release preflight passed for $Tag. Run exactly:"
    Write-Host "git tag $Tag"
    Write-Host "git push origin $Tag"
} finally {
    Pop-Location
}

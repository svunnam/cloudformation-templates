package de.widdix.awscftemplates;

import com.amazonaws.auth.*;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder;
import com.amazonaws.services.route53.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;

import java.util.List;
import java.util.UUID;

public abstract class AAWSTest extends ATest {

    public final static String IAM_SESSION_NAME = "aws-cf-templates";

    protected final AWSCredentialsProvider credentialsProvider;

    private AmazonEC2 ec2;

    private AmazonRoute53 route53;

    private final AmazonS3 s3;

    private final AWSSecurityTokenService sts;

    public AAWSTest() {
        super();
        if (Config.has(Config.Key.IAM_ROLE_ARN)) {
            final AWSSecurityTokenService local = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(new DefaultAWSCredentialsProviderChain()).build();
            this.credentialsProvider = new STSAssumeRoleSessionCredentialsProvider.Builder(Config.get(Config.Key.IAM_ROLE_ARN), IAM_SESSION_NAME).withStsClient(local).build();
        } else {
            this.credentialsProvider = new DefaultAWSCredentialsProviderChain();
        }
        this.ec2 = AmazonEC2ClientBuilder.standard().withCredentials(this.credentialsProvider).build();
        this.route53 = AmazonRoute53ClientBuilder.standard().withCredentials(this.credentialsProvider).build();
        this.s3 = AmazonS3ClientBuilder.standard().withCredentials(this.credentialsProvider).build();
        this.sts = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(this.credentialsProvider).build();
    }

    protected final KeyPair createKey(final String keyName) {
        final CreateKeyPairResult res = this.ec2.createKeyPair(new CreateKeyPairRequest().withKeyName(keyName));
        System.out.println("keypair[" + keyName + "] created: " + res.getKeyPair().getKeyMaterial());
        return res.getKeyPair();
    }

    protected final void deleteKey(final String keyName) {
        if (Config.get(Config.Key.DELETION_POLICY).equals("delete")) {
            this.ec2.deleteKeyPair(new DeleteKeyPairRequest().withKeyName(keyName));
            System.out.println("keypair[" + keyName + "] deleted");
        }
    }

    private void waitForDomain(final String name, final String changeId, final ChangeStatus finalStatus) {
        System.out.println("waitForDomain[" + name + "]: to reach status " + finalStatus);
        while (true) {
            try {
                Thread.sleep(5000);
            } catch (final InterruptedException e) {
                // continue
            }
            final GetChangeResult res = this.route53.getChange(new GetChangeRequest().withId(changeId));
            final ChangeStatus currentStatus = ChangeStatus.fromValue(res.getChangeInfo().getStatus());
            if (finalStatus == currentStatus) {
                System.out.println("waitForDomain[" + name + "]: final status reached.");
                return;
            } else {
                System.out.println("waitForDomain[" + name + "]: continue to wait (still in intermediate status " + currentStatus + ") ...");
            }
        }
    }

    protected final String generateDomain(final String prefix) {
        return prefix + "." + Config.get(Config.Key.DOMAIN_SUFFIX);
    }

    protected final String createDomain(final String prefix, final String host) {
        final String name = this.generateDomain(prefix);
        final ResourceRecord rr = new ResourceRecord(host);
        final ResourceRecordSet rrs = new ResourceRecordSet(name, RRType.CNAME).withTTL(60L).withResourceRecords(rr);
        final Change create = new Change().withAction(ChangeAction.CREATE).withResourceRecordSet(rrs);
        final ChangeBatch changeBatch = new ChangeBatch().withChanges(create);
        final ChangeResourceRecordSetsRequest req = new ChangeResourceRecordSetsRequest().withHostedZoneId(Config.get(Config.Key.HOSTED_ZONE_ID)).withChangeBatch(changeBatch);
        final ChangeResourceRecordSetsResult res = this.route53.changeResourceRecordSets(req);
        this.waitForDomain(name, res.getChangeInfo().getId(), ChangeStatus.INSYNC);
        return name;
    }

    protected final void deleteDomain(final String prefix) {
        if (Config.get(Config.Key.DELETION_POLICY).equals("delete")) {
            final String name = this.generateDomain(prefix);
            final ListResourceRecordSetsResult res1 = this.route53.listResourceRecordSets(new ListResourceRecordSetsRequest().withHostedZoneId(Config.get(Config.Key.HOSTED_ZONE_ID)).withStartRecordName(name));
            final ResourceRecordSet rrs = res1.getResourceRecordSets().get(0);
            final Change delete = new Change().withAction(ChangeAction.DELETE).withResourceRecordSet(rrs);
            final ChangeBatch changeBatch = new ChangeBatch().withChanges(delete);
            final ChangeResourceRecordSetsRequest req = new ChangeResourceRecordSetsRequest().withHostedZoneId(Config.get(Config.Key.HOSTED_ZONE_ID)).withChangeBatch(changeBatch);
            final ChangeResourceRecordSetsResult res2 = this.route53.changeResourceRecordSets(req);
            this.waitForDomain(name, res2.getChangeInfo().getId(), ChangeStatus.INSYNC);
        }
    }

    protected final void createBucket(final String name, final String policy) {
        this.s3.createBucket(new CreateBucketRequest(name, Region.fromValue(this.getRegion())));
        this.s3.setBucketPolicy(name, policy);
    }

    protected final void emptyBucket(final String name) {
        ObjectListing objectListing = s3.listObjects(name);
        while (true) {
            objectListing.getObjectSummaries().forEach((summary) -> s3.deleteObject(name, summary.getKey()));
            if (objectListing.isTruncated()) {
                objectListing = s3.listNextBatchOfObjects(objectListing);
            } else {
                break;
            }
        }
        VersionListing versionListing = s3.listVersions(new ListVersionsRequest().withBucketName(name));
        while (true) {
            versionListing.getVersionSummaries().forEach((vs) -> s3.deleteVersion(name, vs.getKey(), vs.getVersionId()));
            if (versionListing.isTruncated()) {
                versionListing = s3.listNextBatchOfVersions(versionListing);
            } else {
                break;
            }
        }
    }

    protected final void deleteBucket(final String name) {
        this.emptyBucket(name);
        this.s3.deleteBucket(new DeleteBucketRequest(name));
    }

    protected final Vpc getDefaultVPC() {
        final DescribeVpcsResult res = this.ec2.describeVpcs(new DescribeVpcsRequest().withFilters(new Filter().withName("isDefault").withValues("true")));
        return res.getVpcs().get(0);
    }

    protected final List<Subnet> getDefaultSubnets() {
        final DescribeSubnetsResult res = this.ec2.describeSubnets(new DescribeSubnetsRequest().withFilters(new Filter().withName("defaultForAz").withValues("true")));
        return res.getSubnets();
    }

    protected final SecurityGroup getDefaultSecurityGroup() {
        final Vpc vpc = this.getDefaultVPC();
        final DescribeSecurityGroupsResult res = this.ec2.describeSecurityGroups(new DescribeSecurityGroupsRequest().withFilters(
                new Filter().withName("vpc-id").withValues(vpc.getVpcId()),
                new Filter().withName("group-name").withValues("default")
        ));
        return res.getSecurityGroups().get(0);
    }

    protected final String getRegion() {
        return new DefaultAwsRegionProviderChain().getRegion();
    }

    protected final String getAccount() {
        return this.sts.getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
    }

    protected final String random8String() {
        final String uuid = UUID.randomUUID().toString().replace("-", "").toLowerCase();
        final int beginIndex = (int) (Math.random() * (uuid.length() - 7));
        final int endIndex = beginIndex + 7;
        return "r" + uuid.substring(beginIndex, endIndex); // must begin [a-z]
    }

}

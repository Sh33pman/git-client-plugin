package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.Credentials;
import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.util.IOUtils;
import org.eclipse.jgit.api.AddNoteCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ShowNoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevFlagSet;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.MaxCountRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jenkinsci.plugins.gitclient.trilead.CredentialsProviderImpl;
import org.jenkinsci.plugins.gitclient.trilead.TrileadSessionFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang.StringUtils.*;
import static org.eclipse.jgit.api.ResetCommand.ResetType.*;
import static org.eclipse.jgit.lib.Constants.*;

/**
 * GitClient pure Java implementation using JGit.
 * Goal is to eventually get a full java implementation for GitClient
 * <b>
 * For internal use only, don't use directly. See {@link org.jenkinsci.plugins.gitclient.Git}
 * </b>
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @author Kohsuke Kawaguchi
 */
public class JGitAPIImpl extends LegacyCompatibleGitAPIImpl {

    private final TaskListener listener;
    private PersonIdent author, committer;

    private CredentialsProvider provider;

    /**
     * Opened {@link Repository} object.
     * This object cannot be passed to the caller, but so long as its use
     * stays inside this class, use this instance for more efficiency.
     *
     * Use {@link #db()} to access this.
     */
    private Repository db;
    private ObjectReader or;

    JGitAPIImpl(File workspace, TaskListener listener) {
        super(workspace);
        this.listener = listener;

        // to avoid rogue plugins from clobbering what we use, always
        // make a point of overwriting it with ours.
        SshSessionFactory.setInstance(new TrileadSessionFactory());
    }

    public void setCredentials(Credentials cred) {
        setCredentialsProvider(new CredentialsProviderImpl(listener,cred));
    }

    public void setCredentialsProvider(CredentialsProvider prov) {
        this.provider = prov;
    }

    private Repository db() throws GitException {
        if (db==null) {
            db = getRepository();
            or = db.newObjectReader();
        }
        return db;
    }

    public GitClient subGit(String subdir) {
        return new JGitAPIImpl(new File(workspace, subdir), listener);
    }

    public void setAuthor(String name, String email) throws GitException {
        author = new PersonIdent(name,email);
    }

    public void setCommitter(String name, String email) throws GitException {
        committer = new PersonIdent(name,email);
    }

    public void init() throws GitException {
        try {
            Git.init().setDirectory(workspace).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void checkout(String ref) throws GitException {
        try {
            git().checkout().setName(ref).setForce(true).call();
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + ref, e);
        }
    }

    public void checkout(String ref, String branch) throws GitException {
        try {
            if (ref == null) ref = db().resolve(HEAD).name();
            Git git = Git.wrap(db());
            git.checkout().setName(branch).setCreateBranch(true).setForce(true).setStartPoint(ref).call();
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }

    public void checkoutBranch(String branch, String ref) throws GitException {
        try {
            RefUpdate refUpdate = db().updateRef(R_HEADS + branch);
            refUpdate.setNewObjectId(db().resolve(ref));
            switch (refUpdate.forceUpdate()) {
            case NOT_ATTEMPTED:
            case LOCK_FAILURE:
            case REJECTED:
            case REJECTED_CURRENT_BRANCH:
            case IO_FAILURE:
            case RENAMED:
                throw new GitException("Could not update " + branch + " to " + ref);
            }

            checkout(ref);
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
        }
    }


    public void add(String filePattern) throws GitException {
        try {
            git().add().addFilepattern(filePattern).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    private Git git() {
        return Git.wrap(getRepository());
    }

    public void commit(String message) throws GitException {
        try {
            CommitCommand cmd = git().commit().setMessage(message);
            if (author!=null)
                cmd.setAuthor(author);
            if (committer!=null)
                cmd.setCommitter(new PersonIdent(committer,new Date()));
            cmd.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void branch(String name) throws GitException {
        try {
            git().branchCreate().setName(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void deleteBranch(String name) throws GitException {
        try {
            git().branchDelete().setForce(true).setBranchNames(name).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<Branch> getBranches() throws GitException {
        try {
            List<Ref> refs = git().branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<Branch> getRemoteBranches() throws GitException {
        try {
            List<Ref> refs = git().branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void tag(String name, String message) throws GitException {
        try {
            git().tag().setName(name).setMessage(message).setForceUpdate(true).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public boolean tagExists(String tagName) throws GitException {
        try {
            Ref tag =  db().getRefDatabase().getRef(R_TAGS + tagName);
            return tag != null;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }


    public void fetch(String remoteName, RefSpec refspec) throws GitException {
        try {
            Git git = Git.wrap(getRepository());
            FetchCommand fetch = git.fetch().setTagOpt(TagOpt.FETCH_TAGS);
            if (remoteName != null) fetch.setRemote(remoteName);
            fetch.setCredentialsProvider(provider);

            // see http://stackoverflow.com/questions/14876321/jgit-fetch-dont-update-tag
            List<RefSpec> refSpecs = new ArrayList<RefSpec>();
            refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
            if (refspec != null) refSpecs.add(refspec);
            fetch.setRefSpecs(refSpecs);

            fetch.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException {
        try {
            if (!branch.startsWith(R_HEADS))
                branch = R_HEADS+branch;

            Repository repo = openDummyRepository();
            final Transport tn = Transport.open(repo, new URIish(remoteRepoUrl));
            tn.setCredentialsProvider(provider);
            final FetchConnection c = tn.openFetch();
            try {
                for (final Ref r : c.getRefs()) {
                    if (branch.equals(r.getName())) {
                        return r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
                    }
                }
            } finally {
                c.close();
                tn.close();
                repo.close();
            }
        } catch (IOException e) {
            throw new GitException(e);
        } catch (URISyntaxException e) {
            throw new GitException(e);
        }
        return null;
    }

    /**
     * Creates a empty dummy {@link Repository} to keep JGit happy where it wants a valid {@link Repository} operation
     * for remote objects.
     */
    private Repository openDummyRepository() throws IOException {
        final File tempDir = Util.createTempDir();
        return new FileRepository(tempDir) {
            @Override
            public void close() {
                super.close();
                try {
                    Util.deleteRecursive(tempDir);
                } catch (IOException e) {
                    // ignore
                }
            }
        };
    }

    public String getRemoteUrl(String name) throws GitException {
        return db().getConfig().getString("remote",name,"url");
    }

    public Repository getRepository() throws GitException {
        try {
            return new RepositoryBuilder().setWorkTree(workspace).build();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    public void merge(ObjectId rev) throws GitException {
        try {
            Git git = git();
            MergeResult mergeResult = git.merge().include(rev).call();
            if (!mergeResult.getMergeStatus().isSuccessful()) {
                git.reset().setMode(HARD).call();
                throw new GitException("Failed to merge " + rev);
            }
        } catch (GitAPIException e) {
            throw new GitException("Failed to merge " + rev, e);
        }
    }

    public void setRemoteUrl(String name, String url) throws GitException {
        try {
            StoredConfig config = db().getConfig();
            config.setString("remote", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }


    public void addNote(String note, String namespace) throws GitException {
        try {
            ObjectId head = db().resolve(HEAD); // commit to put a note on

            AddNoteCommand cmd = git().notesAdd();
            cmd.setMessage(normalizeNote(note));
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            RevWalk walk = new RevWalk(or);
            cmd.setObjectId(walk.parseAny(head));
            cmd.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * Git-notes normalizes newlines.
     *
     * This behaviour is reverse engineered from limited experiments, so it may be incomplete.
     */
    private String normalizeNote(String note) {
        note = note.trim();
        note = note.replaceAll("\r\n","\n").replaceAll("\n{3,}","\n\n");
        note += "\n";
        return note;
    }

    private String qualifyNotesNamespace(String namespace) {
        if (!namespace.startsWith("refs/")) namespace = "refs/notes/"+namespace;
        return namespace;
    }

    public void appendNote(String note, String namespace) throws GitException {
        try {
            ObjectId head = db().resolve(HEAD); // commit to put a note on

            ShowNoteCommand cmd = git().notesShow();
            cmd.setNotesRef(qualifyNotesNamespace(namespace));
            RevWalk walk = new RevWalk(or);
            cmd.setObjectId(walk.parseAny(head));
            Note n = cmd.call();

            if (n==null) {
                addNote(note,namespace);
            } else {
                ObjectLoader ol = or.open(n.getData());
                StringWriter sw = new StringWriter();
                IOUtils.copy(new InputStreamReader(ol.openStream(),CHARSET),sw);
                sw.write("\n");
                addNote(sw.toString() + normalizeNote(note), namespace);
            }
        } catch (GitAPIException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public ChangelogCommand changelog() {
        db();   // ensure 'or' is set
        return new ChangelogCommand() {
            RevWalk walk = new RevWalk(or);
            Writer out;

            public ChangelogCommand excludes(String rev) {
                try {
                    return excludes(db().resolve(rev));
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand excludes(ObjectId rev) {
                try {
                    walk.markUninteresting(walk.lookupCommit(rev));
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand includes(String rev) {
                try {
                    return includes(db().resolve(rev));
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand includes(ObjectId rev) {
                try {
                    walk.markStart(walk.lookupCommit(rev));
                    return this;
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }

            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            public ChangelogCommand max(int n) {
                walk.setRevFilter(MaxCountRevFilter.create(n));
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                PrintWriter pw = new PrintWriter(out,false);
                try {
                    final RenameDetector rd = new RenameDetector(db());

                    for (RevCommit commit : walk) {
                        // git whatachanged doesn't show the merge commits unless -m is given
                        if (commit.getParentCount()>1)  continue;

                        pw.printf("commit %s\n", commit.name());
                        pw.printf("tree %s\n", commit.getTree().name());
                        for (RevCommit parent : commit.getParents())
                            pw.printf("parent %s\n",parent.name());
                        pw.printf("author %s\n", commit.getAuthorIdent().toExternalString());
                        pw.printf("committer %s\n", commit.getCommitterIdent().toExternalString());

                        // indent commit messages by 4 chars
                        String msg = commit.getFullMessage();
                        if (msg.endsWith("\n")) msg=msg.substring(0,msg.length()-1);
                        msg = msg.replace("\n","\n    ");
                        msg="    "+msg+"\n";

                        pw.println(msg);

                        // see man git-diff-tree for the format
                        TreeWalk tw = TreeWalk.forPath(or, "", commit.getParent(0).getTree(), commit.getTree());
                        tw.setRecursive(true);
                        tw.setFilter(TreeFilter.ANY_DIFF);

                        rd.reset();
                        rd.addAll(DiffEntry.scan(tw));
                        List<DiffEntry> diffs = rd.compute(or, null);
                        for (DiffEntry diff : diffs) {
                            pw.printf(":%06o %06o %s %s %s %s",
                                    diff.getOldMode().getBits(),
                                    diff.getNewMode().getBits(),
                                    diff.getOldId().name(),
                                    diff.getNewId().name(),
                                    statusOf(diff),
                                    diff.getOldPath());

                            if (hasNewPath(diff)) {
                                pw.printf(" %s",diff.getNewPath()); // copied to
                            }
                            pw.println();
                            pw.println();
                        }
                    }
                } catch (IOException e) {
                    throw new GitException(e);
                } finally {
                    pw.flush();
                }
            }

            private boolean hasNewPath(DiffEntry d) {
                return d.getChangeType()==ChangeType.COPY || d.getChangeType()==ChangeType.RENAME;
            }

            private String statusOf(DiffEntry d) {
                switch (d.getChangeType()) {
                case ADD:       return "A";
                case MODIFY:    return "M";
                case DELETE:    return "D";
                case RENAME:    return "R"+d.getScore();
                case COPY:      return "C"+d.getScore();
                default:
                    throw new AssertionError("Unexpected change type: "+d.getChangeType());
                }
            }
        };
    }

    public void clean() throws GitException {
        try {
            Git git = git();
            git.reset().setMode(HARD).call();
            git.clean().setCleanDirectories(true).setIgnore(false).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public CloneCommand clone_() {
        final org.eclipse.jgit.api.CloneCommand base = new org.eclipse.jgit.api.CloneCommand();
        base.setDirectory(workspace);
        base.setProgressMonitor(new ProgressMonitor(listener));
        base.setCredentialsProvider(provider);

        return new CloneCommand() {

            public CloneCommand url(String url) {
                base.setURI(url);
                return this;
            }

            public CloneCommand repositoryName(String name) {
                base.setRemote(name);
                return this;
            }

            public CloneCommand shallow() {
                listener.getLogger().println("[WARNING] JGit doesn't support shallow clone. This flag is ignored");
                return this;
            }

            public CloneCommand reference(String reference) {
                listener.getLogger().println("[WARNING] JGit doesn't support reference repository. This flag is ignored.");
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                try {
                    // the directory needs to be clean or else JGit complains
                    if (workspace.exists())
                        Util.deleteContentsRecursive(workspace);

                    base.call();
                } catch (GitAPIException e) {
                    throw new GitException(e);
                } catch (IOException e) {
                    throw new GitException(e);
                }
            }
        };
    }

    public void deleteTag(String tagName) throws GitException {
        try {
            git().tagDelete().setTags(tagName).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public String getTagMessage(String tagName) throws GitException {
        try {
            db();
            RevWalk walk = new RevWalk(or);
            String s = walk.parseTag(db().resolve(tagName)).getFullMessage();
            walk.dispose();
            return s.trim();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        try {
            List<IndexEntry> r = new ArrayList<IndexEntry>();

            Repository db = db();
            RevWalk w=new RevWalk(or);
            RevTree t = w.parseTree(db.resolve(treeIsh));
            SubmoduleWalk walk = new SubmoduleWalk(db);
            walk.setTree(t);
            walk.setRootTree(t);
            while (walk.next()) {
                r.add(new IndexEntry(walk));
            }

            return r;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public void addSubmodule(String remoteURL, String subdir) throws GitException {
        try {
            git().submoduleAdd().setPath(subdir).setURI(remoteURL).call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        if (tagPattern == null) tagPattern = "*";

        try {
            Set<String> tags = new HashSet<String>();
            FileNameMatcher matcher = new FileNameMatcher(tagPattern, '/');
            Map<String, Ref> refList = db().getRefDatabase().getRefs(R_TAGS);
            for (Ref ref : refList.values()) {
                String name = ref.getName().substring(R_TAGS.length());
                matcher.reset();
                matcher.append(name);
                if (matcher.isMatch()) tags.add(name);
            }
            return tags;
        } catch (IOException e) {
            throw new GitException(e);
        } catch (InvalidPatternException e) {
            throw new GitException(e);
        }
    }

    public boolean hasGitRepo() throws GitException {
        Repository db = null;
        try {
            db = getRepository();
            return db.getObjectDatabase().exists();
        } catch (GitException e) {
            return false;
        } finally {
            if (db != null) db.close();
        }
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        return db().hasObject(commit);
    }

    public void prune(RemoteConfig repository) throws GitException {
        try {
            String remote = repository.getName();
            String prefix = "refs/remotes/" + remote + "/";

            Set<String> branches = listRemoteBranches(remote);

            for (Ref r : new ArrayList<Ref>(db().getAllRefs().values())) {
                if (r.getName().startsWith(prefix) && !branches.contains(r.getName())) {
                    // delete this ref
                    RefUpdate update = db().updateRef(r.getName());
                    update.setRefLogMessage("remote branch pruned", false);
                    update.setForceUpdate(true);
                    Result res = update.delete();
                }
            }
        } catch (URISyntaxException e) {
            throw new GitException(e);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    private Set<String> listRemoteBranches(String remote) throws NotSupportedException, TransportException, URISyntaxException {
        StoredConfig config = db().getConfig();

        Set<String> branches = new HashSet<String>();
        final Transport tn = Transport.open(db(), new URIish(config.getString("remote",remote,"url")));
        tn.setCredentialsProvider(provider);
        final FetchConnection c = tn.openFetch();
        try {
            for (final Ref r : c.getRefs()) {
                if (r.getName().startsWith(R_HEADS))
                    branches.add("refs/remotes/"+remote+"/"+r.getName().substring(R_HEADS.length()));
            }
        } finally {
            c.close();
            tn.close();
        }
        return branches;
    }

    public void push(String remoteName, String refspec) throws GitException {
        RefSpec ref = (refspec != null) ? new RefSpec(refspec) : Transport.REFSPEC_PUSH_ALL;
        try {
            git().push().setRemote(remoteName).setRefSpecs(ref)
                    .setProgressMonitor(new ProgressMonitor(listener))
                    .setCredentialsProvider(provider)
                    .call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public List<ObjectId> revListAll() throws GitException {
        try {
            db();
            RevWalk walk = new RevWalk(or);
            for (Ref r : db().getAllRefs().values()) {
                walk.markStart(walk.parseCommit(r.getObjectId()));
            }
            return revList(walk);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public List<ObjectId> revList(String ref) throws GitException {
        try {
            db();
            RevWalk walk = new RevWalk(or);
            walk.markStart(walk.parseCommit(db().resolve(ref)));
            return revList(walk);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    private List<ObjectId> revList(RevWalk walk) {
        walk.setRetainBody(false);
        walk.sort(RevSort.COMMIT_TIME_DESC);

        List<ObjectId> r = new ArrayList<ObjectId>();
        for (RevCommit c : walk) {
            r.add(c.copy());
        }
        return r;
    }

    public ObjectId revParse(String revName) throws GitException {
        try {
            ObjectId id = db().resolve(revName + "^{commit}");
            if (id == null)
                throw new GitException("Unknown git object "+ revName);
            return id;
        } catch (IOException e) {
            throw new GitException("Failed to resolve git reference "+ revName, e);
        }
    }

    public List<String> showRevision(ObjectId r) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void submoduleClean(boolean recursive) throws GitException {
        try {
            SubmoduleWalk generator = SubmoduleWalk.forIndex(db());
            while (generator.next()) {
                JGitAPIImpl subgit = new JGitAPIImpl(generator.getDirectory(), listener);
                subgit.clean();
                if (recursive) {
                    subgit.submoduleClean(true);
                }
            }
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public void submoduleUpdate(boolean recursive) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }





    //
    //
    // Legacy Implementation of IGitAPI
    //
    //

    @Deprecated
    public void merge(String refSpec) throws GitException, InterruptedException {
        try {
            merge(db().resolve(refSpec));
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        push(repository.getName(),refspec);
    }

    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
        try {
            db();
            RevWalk walk = new RevWalk(or);
            walk.setRetainBody(false);  // we don't need the body for this computation
            walk.setRevFilter(RevFilter.MERGE_BASE);

            walk.markStart(walk.parseCommit(id1));
            walk.markStart(walk.parseCommit(id2));

            RevCommit base = walk.next();
            if (base==null)     return null;    // no common base
            return base.getId();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public String getAllLogEntries(String branch) throws InterruptedException {
        try {
            StringBuilder w = new StringBuilder();

            db();
            RevWalk walk = new RevWalk(or);
            for (Ref r : db().getAllRefs().values()) {
                walk.markStart(walk.parseCommit(r.getObjectId()));
            }
            walk.setRetainBody(false);

            for (RevCommit c : walk) {
                w.append('\'').append(c.name()).append('#').append(c.getCommitTime()).append("'\n");
            }
            return w.toString().trim();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public void submoduleInit() throws GitException, InterruptedException {
        try {
            git().submoduleInit().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public void submoduleSync() throws GitException, InterruptedException {
        try {
            git().submoduleSync().call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String v = db().getConfig().getString("submodule", name, "url");
        if (v==null)    throw new GitException("No such submodule: "+name);
        return v.trim();
    }

    @Deprecated
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        try {
            StoredConfig config = db().getConfig();
            config.setString("submodule", name, "url", url);
            config.save();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    /**
     * I don't think anyone is using this method, and I don't think we ever need to implement this.
     *
     * This kind of logic doesn't belong here, as it lacks generality. It should be
     * whoever manipulating Git.
     */
    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /**
     * I don't think anyone is using this method, and I don't think we ever need to implement this.
     *
     * This kind of logic doesn't belong here, as it lacks generality. It should be
     * whoever manipulating Git.
     */
    @Deprecated
    public void fixSubmoduleUrls(String remote, TaskListener listener) throws GitException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    /**
     * This implementation is based on my reading of the cgit source code at https://github.com/git/git/blob/master/builtin/describe.c
     *
     * <p>
     * The basic structure of the algorithm is as follows. We walk the commit graph,
     * find tags, and mark commits that are reachable from those tags. The marking
     * uses flags given by JGit, so there's a fairly small upper bound in the number of tags
     * we can keep track of.
     *
     * <p>
     * As we walk commits, we count commits that each tag doesn't contain.
     * We call it "depth", following the variable name in C Git.
     * As we walk further and find enough tags, we go into wind-down mode and only walk
     * to the point of accurately determining all the depths.
     */
    public String describe(String tip) throws GitException, InterruptedException {
        try {
            db();
            final RevWalk w = new RevWalk(or);
            w.setRetainBody(false);

            Map<ObjectId,Ref> tags = new HashMap<ObjectId, Ref>();
            for (Ref r : db().getTags().values()) {
                ObjectId key = db.peel(r).getPeeledObjectId();
                if (key==null)  key = r.getObjectId();
                tags.put(key, r);
            }

            final RevFlagSet allFlags = new RevFlagSet(); // combined flags of all the Candidate instances

            /**
             * Tracks the depth of each tag as we find them.
             */
            class Candidate {
                final RevCommit commit;
                final Ref tag;
                final RevFlag flag;

                /**
                 * This field number of commits that are reachable from the tip but
                 * not reachable from the tag.
                 */
                int depth;

                Candidate(RevCommit commit, Ref tag) {
                    this.commit = commit;
                    this.tag = tag;
                    this.flag = w.newFlag(tag.getName());
                    // we'll mark all the notes reachable from this tag accordingly
                    w.carry(flag);
                    commit.add(flag);
                    allFlags.add(flag);
                }

                /**
                 * Does this tag contains the given commit?
                 */
                public boolean reaches(RevCommit c) {
                    return c.has(flag);
                }

                public String describe(ObjectId tip) throws IOException {
                    return String.format("%s-%d-g%s", tag.getName().substring(R_TAGS.length()),
                            depth, or.abbreviate(tip).name());
                }
            }
            List<Candidate> candidates = new ArrayList<Candidate>();    // all the candidates we find

            ObjectId tipId = db().resolve(tip);

            Ref lucky = tags.get(tipId);
            if (lucky!=null)
                return lucky.getName().substring(R_TAGS.length());

            w.markStart(w.parseCommit(tipId));

            int maxCandidates = 10;

            int seen = 0;   // commit seen thus far
            RevCommit c;
            while ((c=w.next())!=null) {
                if (!c.hasAny(allFlags)) {
                    // if a tag already dominates this commit,
                    // then there's no point in picking a tag on this commit
                    // since the one that dominates it is always more preferable
                    Ref t = tags.get(c);
                    if (t!=null) {
                        Candidate cd = new Candidate(c, t);
                        candidates.add(cd);
                        cd.depth = seen;
                    }
                }

                // if the newly discovered commit isn't reachable from a tag that we've seen
                // it counts toward the total depth.
                for (Candidate cd : candidates) {
                    if (!cd.reaches(c)) {
                        cd.depth++;
                    }
                }

                // if we have search going for enough tags, we wil start closing down.
                // JGit can only give us a finite number of bits, so we can't track
                // all tags even if we wanted to.
                if (candidates.size()>=maxCandidates)
                    break;

                // TODO: if all the commits in the queue of RevWalk has allFlags
                // there's no point in continuing search as we'll not discover any more
                // tags. But RevWalk doesn't expose this.

                seen++;
            }

            // at this point we aren't adding any more tags to our search,
            // but we still need to count all the depths correctly.
            while ((c=w.next())!=null) {
                if (c.hasAll(allFlags)) {
                    // no point in visiting further from here, so cut the search here
                    for (RevCommit p : c.getParents())
                        p.add(RevFlag.SEEN);
                } else {
                    for (Candidate cd : candidates) {
                        if (!cd.reaches(c)) {
                            cd.depth++;
                        }
                    }
                }
            }

            if (candidates.isEmpty())
                throw new GitException("No tags can describe "+tip);

            // if all the nodes are dominated by all the tags, the walk stops
            Collections.sort(candidates,new Comparator<Candidate>() {
                public int compare(Candidate o1, Candidate o2) {
                    return o1.depth-o2.depth;
                }
            });

            return candidates.get(0).describe(tipId);
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        try {
            db();
            RevWalk w = new RevWalk(or);

            TreeWalk tree = new TreeWalk(or);
            tree.addTree(w.parseTree(db().resolve(treeIsh)));
            tree.setRecursive(recursive);

            List<IndexEntry> r = new ArrayList<IndexEntry>();
            while (tree.next()) {
                RevObject rev = w.parseAny(tree.getObjectId(0));
                r.add(new IndexEntry(
                        String.format("%06o", tree.getRawMode(0)),
                        typeString(rev.getType()),
                        tree.getObjectId(0).name(),
                        tree.getNameString()));
            }
            return r;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public void reset(boolean hard) throws GitException, InterruptedException {
        try {
            ResetCommand reset = new ResetCommand(db());
            reset.setMode(hard?HARD:MIXED);
            reset.call();
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    @Deprecated
    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        if (isBlank(GIT_DIR))
            return db().isBare();
        return new File(workspace,GIT_DIR).getName().equals(".git");
    }

    @Deprecated
    public String getDefaultRemote(String _default_) throws GitException, InterruptedException {
        Set<String> remotes = getConfig(null).getNames("remote");
        if (remotes.contains(_default_))    return _default_;
        else    return com.google.common.collect.Iterables.getFirst(remotes, null);
    }

    @Deprecated
    public void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException, InterruptedException {
        getConfig(GIT_DIR).setString("remote", name, "url", url);
    }

    @Deprecated
    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        return getConfig(GIT_DIR).getString("remote", name, "url");
    }

    private StoredConfig getConfig(String GIT_DIR) {
        StoredConfig config;
        if (isBlank(GIT_DIR))
            config = db().getConfig();
        else
            config = new FileBasedConfig(new File(workspace,GIT_DIR),db().getFS());
        return config;
    }
}

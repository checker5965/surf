package in.edu.ashoka.surf;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import edu.stanford.muse.util.Util;
import in.edu.ashoka.surf.util.Timers;
import in.edu.ashoka.surf.util.UnionFindSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static in.edu.ashoka.surf.MergeManager.RowViewControl.IDS_MATCHING_FILTER;
import static in.edu.ashoka.surf.MergeManager.RowViewControl.ROWS_MATCHING_FILTER;

/** class that takes care of running a merging algorithm and then manual merges to it.
 * Important: these functions should not depend on a web-based frontend. This class should have no dependency on servlets. */
public class MergeManager {
    public static Log log = LogFactory.getLog(in.edu.ashoka.surf.MergeManager.class);

    /** we first decide which groups to show. This determination is independent of which rows are shown. This does not take filter into account, only the algorithmically merged groups */
    public enum GroupViewControl {
        ALL_GROUPS, /* this will show all groups, including singleton rows */
        GROUPS_WITH_TWO_OR_MORE_IDS, /* show all rows in a group, under which any row matches filter */
        GROUPS_WITH_TWO_OR_MORE_ROWS,
        GROUPS_WITH_ONE_OR_MORE_ROWS
    } /* this will show a group even if it only has 1 id, but multiple rows for that id. useful to see all id's that have been merged */

    /* Next, a filter is applied to rows in the groups selected for showing. */
    public enum RowViewControl {
        ALL_ROWS, /* this will show a group even if it only has 1 id, but multiple rows for that id */
        IDS_MATCHING_FILTER, /* if an Id has at least 1 row matching the filter, show all rows for that id */
        ROWS_MATCHING_FILTER /* Only show rows that directly match a filter */
    }

    /** a view is a filtered and sorted view on top of this mergemanager's results */
    public class View {

        public final List<List<List<Row>>> viewGroups; // these are the groups
        public final String filterSpec, sortOrder;
        private int nIds, nRowsInGroups;

        // there are controls with respect to filter
        public GroupViewControl groupViewControl;

        // controls with respect to algorithm's grouping. which groups to show?
        public RowViewControl rowViewControl;

        // both controls above have to be satisfied
        // use cases:
        // 1) see all rows in dataset, including singletons, sorted alphabetically: showOnlyRowsThatMatch (with empty filter), showAllGroups
        // 2) see all rows in dataset that match a search term: showOnlyRowsThatMatch with filter spec, showAllGroups.
        // 3) see all Ids in dataset that match a search term: showIdsWithAnyRowMatch with filter spec, showAllGroups.
        // 4) See groups with merged all merges that have been performed: showGroupsWithAnyRowMatch (with empty filter) showGroupsWithAtLeast2Rows

        private View (String filterSpec, GroupViewControl groupFilter, RowViewControl rowFilter, String sortOrder, List<List<List<Row>>> viewGroups) {
            this.filterSpec = filterSpec;
            this.sortOrder = sortOrder;
            this.viewGroups = viewGroups;

            nRowsInGroups = 0;
            Set<String> idsSeen = new LinkedHashSet<>();
            for (List<List<Row>> rowsForThisGroup: viewGroups) {
                for (List<Row> rowsForThisId: rowsForThisGroup)
                    for (Row row: rowsForThisId) {
                        idsSeen.add(row.get(Config.ID_FIELD));
                        nRowsInGroups++;
                    }
            }
            nIds = idsSeen.size();

            this.groupViewControl = groupFilter;
            this.rowViewControl = rowFilter;
        }

        public MergeManager getMergeManager () { return MergeManager.this; }

        public String description() {
            return  MergeManager.this.d.description + "\nAlgorithm: " + MergeManager.this.algorithm.toString() +
                    (!Util.nullOrEmpty(MergeManager.this.splitColumn) ? " (further split by " + splitColumn + ")" : "") + "\n"
                    + Util.commatize(viewGroups.size()) + " groups with " + Util.commatize(nRowsInGroups) + " rows (of " + Util.commatize(MergeManager.this.d.getRows().size())
                    + ") with " + Util.commatize(nIds) + " unique ids";
        }

        public String toString() { return "View: #groups " + viewGroups + " filterSpec = " + filterSpec + " sortOrder = " + sortOrder + " ";}
    }

	private Dataset d;
    private List<Collection<Row>> groups; // these are the groups

    /** nextAvailableID and idToRows should be updated in sync.
     * nextAvailableID is the integer beyond which all integers can be used as IDs (after conversion to strings), i.e. they are not used as id's for any rows.
     * e.g. if nextAvailableID is 5000, any integer > 5000 can be converted to a string and safely used as an ID without conflicting with existing IDs.
     * This is useful when rows under an ID are unmerged and have to be assigned new IDs.
     */
    private Multimap<String, Row> idToRows = LinkedHashMultimap.create();
    private SetMultimap<Row, String> rowToLabels = HashMultimap.create();
    public int nextAvailableId = 0;

    List<Command> allCommands = new ArrayList<>(); // compile all the commands, so that they can be replayed some day, if needed

    private MergeAlgorithm algorithm;
    private String splitColumn;
    public View lastView; // currently we assume only 1 view

    // a small class to represent operations made on this dataset.
    // op is the operation to run: merge, or unmerge
    // the merge or unmerge is run on the given ids.
    public static class Command {
        private static final String MERGE_ID_DELIMITER = ";"; // this string should not be allowed to be part of any ID
        String op;
        String groupId; // not needed and not used currently
        String label;
        String[] ids;

        // e.g.
        // merge: 100; 200; 300
        // unmerge: 1000
        // add-label: reviewed 100;200;300
        // remove-label: reviewed 400;500;600
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(op);
            sb.append(":");
            if (!Util.nullOrEmpty(label))
                sb.append(label);
            for (String id : ids) {
                sb.append(id);
                sb.append(MERGE_ID_DELIMITER); // delimite ID's with
            }
            return sb.toString();
        }
    }

    /** create a new mergeManager with the given algorithm and arguments, and runs the algorithm and stores the (initial) groups.
     * further splits by splitColumn if it's not null or empty */
    public MergeManager(Dataset dataset, Map<String, String> params) throws FileNotFoundException {
        this.d = dataset;
        computeIdToRows(d.getRows());

        String filterSpec = params.get ("filterSpec");
        Filter filter = new Filter (filterSpec);
        String algo = params.get ("algorithm");

        Tokenizer.setupDesiVersions(dataset.getRows(), Config.MERGE_FIELD);

        if (algo.equals("editDistance")) {
            int editDistance = Config.DEFAULT_EDIT_DISTANCE;
            try {
                editDistance = Integer.parseInt(params.get("edit-distance"));
            } catch (NumberFormatException e) {
                Util.print_exception(e, log);
            }
            algorithm = new EditDistanceMergeAlgorithm(d, "_st_" + Config.MERGE_FIELD, editDistance, filter); // run e.d. on the _st_ version of the field
        } else if (algo.equals("allNames")) {
            algorithm = new MergeAlgorithm(dataset) {
                @Override
                public List<Collection<Row>> run() {
                    classes = new ArrayList<>();
                    classes.add(d.getRows().stream().filter (filter::passes).collect(Collectors.toList())); // just one class, with all the rows in it
                    return classes;
                }
                public String toString() { return "Dummy merge"; }
            };
        } else if (algo.equals("compatibleNames")) {
            int minTokenOverlap = Config.DEFAULT_MIN_TOKEN_OVERLAP;
            try {
                minTokenOverlap = Integer.parseInt(params.get("min-token-overlap"));
            } catch (NumberFormatException e) {
                Util.print_exception(e, log);
            }
            int ignoreTokenFrequency = Config.DEFAULT_IGNORE_TOKEN_FREQUENCY;
            try {
                ignoreTokenFrequency = Integer.parseInt(params.get("ignore-token-frequency"));
            } catch (NumberFormatException e) {
                Util.print_exception(e, log);
            }

            boolean initialMapping = "on".equals(params.get("initialMapping"));
            boolean substringAllowed = "on".equals(params.get("substringAllowed"));
//            String fieldToCompare = "_c_" + Config.MERGE_FIELD; // we run it on the canon version of the name, not the tokenized, because that causes too many merges
            String fieldToCompare = "_st_" + Config.MERGE_FIELD; // we run it on tokenized version of the name now that we have tightened it. go back to _c_name if this causes too many matches
            algorithm = new CompatibleNameAlgorithm(d, fieldToCompare, filter, minTokenOverlap, ignoreTokenFrequency, substringAllowed, initialMapping);
        }

        // this is where the groups are generated
        groups = algorithm.run();
        rowToLabels.clear(); // remove all the old labels the moment a new alg. is run
        // if the dataset already had some id's the same, merge them
        updateMergesBasedOnIds();
        this.splitColumn = params.get("splitColumn");
        if (!Util.nullOrEmpty(splitColumn))
            splitByColumn (splitColumn);
    }

    public void addLabel (Row r, String label) {
        rowToLabels.put (r, label);
    }

    public boolean removeLabel (Row r, String label) {
        return rowToLabels.remove(r, label);
    }

    public boolean hasLabel (Row r, String label) {
        Collection<String> labels = rowToLabels.get (r);
        return labels != null && labels.contains (label);
    }

    /** will split groups into new groups based on the split column */
    private void splitByColumn (String splitColumn) {
        // further split by split column if specified
        if (Util.nullOrEmpty(splitColumn))
            return;

        List<Collection<Row>> result = new ArrayList<>();
        List<Collection<Row>> groupsList = this.groups;
        for (Collection<Row> group : groupsList) {
            Multimap<String, Row> splitGroups = SurfExcel.split(group, splitColumn);
            for (String key : splitGroups.keySet())
                result.add(splitGroups.get(key));
        }
        this.groups = result;
    }

    /** updates groups based on id's as well as existing groups.
     * MUST be called after id's are changed */
    void updateMergesBasedOnIds() {
        Timers.unionFindTimer.reset();
        Timers.unionFindTimer.start();

        int initialClusters = groups.size();
        UnionFindSet<Row> ufs = new UnionFindSet<>();

        // do unification, the criteria are based on one of 2 factors
        // same id, or same cluster according to groups

        for (String id: idToRows.keySet()) {
            ufs.unifyAllElementsOfCollection (idToRows.get(id));
        }

        for (Collection<Row> cluster : groups) {
            ufs.unifyAllElementsOfCollection(cluster);
        }

        Timers.unionFindTimer.stop();
        Timers.log.info ("Time for union-find: " + Timers.unionFindTimer.toString());
        groups = (List) ufs.getClassesSortedByClassSize();

        // recompute id -> rows map
        computeIdToRows(d.getRows());
        log.info ("initial # of groups " + initialClusters + ", after merging by id, we have " + groups.size() + " groups");
    }

    /* computes idToRows and also updates nextAvailableId */
    private void computeIdToRows (Collection<Row> rows) {
        for (Row r: rows)
            idToRows.put (r.get(Config.ID_FIELD), r);

        int maxNumberUsed = 1;
        for (String id: idToRows.keySet()) {
            int x = 0;
            try { x = Integer.parseInt(id); } catch (NumberFormatException nfe) { continue; }
            if (x > maxNumberUsed)
                maxNumberUsed = x;
        }

        nextAvailableId = maxNumberUsed + 1;
    }

    /** applies the given merge/unmerge commands on the dataset */
    public void applyUpdatesAndSave(Command[] commands) throws IOException {
        allCommands.addAll (Arrays.asList(commands));

        log.info ("Applying " + commands.length + " command(s) to " + d);

        for (Command command: commands) {
            if ("merge".equalsIgnoreCase(command.op)) {
                // we have to merge all the ids in command.ids
                // the id of the first will be put into firstId, and copied to all the other rows with that id
                String firstId = null;
                for (String id : command.ids) {
                    if (firstId == null) {
                        firstId = id;
                        continue;
                    }

                    // update all the rows for this id to firstId
                    // also remember to update the idToRows map
                    log.info("Merging id " + id + " into " + firstId);
                    Collection<Row> rowsForThisId = idToRows.get(id);
                    for (Row row : rowsForThisId) {
                        row.set(Config.ID_FIELD, firstId); // we wipe out the old id for this row
                        idToRows.get(firstId).add (row);
                    }
                    idToRows.removeAll (id); // remove this id entirely from the map, it will not be used again
                }
                updateMergesBasedOnIds(); // is this needed now, or can it be done outside the loop?
            } else if ("unmerge".equalsIgnoreCase(command.op)) {
                // create unique id's for all rows
                for (String id : command.ids) {
                    Collection<Row> rowsForThisId = idToRows.get(id);
                    for (Row row : rowsForThisId) {
                        row.set(Config.ID_FIELD, Integer.toString(nextAvailableId));
                        nextAvailableId++;
                    }
                }
            } else if ("add-label".equalsIgnoreCase(command.op)) {
                String label = command.label;
                for (String gid : command.ids) {
                    List<List<Row>> rowsForThisGid = lastView.viewGroups.get(Integer.parseInt(gid));

                    for (List<Row> rowsForThisId : rowsForThisGid) {
                        for (Row row : rowsForThisId) {
                            addLabel(row, label);
                        }
                    }
                }
            } else if ("remove-label".equalsIgnoreCase(command.op)) {
                String label = command.label;
                for (String gid : command.ids) {
                    List<List<Row>> rowsForThisGid = lastView.viewGroups.get(Integer.parseInt(gid));

                    for (List<Row> rowsForThisId : rowsForThisGid) {
                        for (Row row : rowsForThisId) {
                            removeLabel(row, label);
                        }
                    }
                }
            }
        }

        d.save();
    }

    public View getView (String filterSpec, GroupViewControl groupFilter, RowViewControl rowFilter, String sortOrder) {
        List<List<List<Row>>> postFilterGroups = applyFilter(new Filter(filterSpec), groupFilter, rowFilter);
        Comparator<List<List<Row>>> comparator = GroupOrdering.getComparator (sortOrder);
        postFilterGroups.sort (comparator);

        /** use this to dump all the groups for debugging
        for (List<List<Row>> group: postFilterGroups) {
            StringBuilder sb = new StringBuilder();
            int length = 0, count = 0, num = 0;
            for (List<Row> rows: group) {
                for (Row row: rows) {
                    length += row.get(Config.MERGE_FIELD).length();
                    count++;
                    num++;
                    sb.append(row.get(Config.MERGE_FIELD) + " ");
                }
            }
            System.out.println ("Group #" + num + ": " + sb + " #rows in group = " + count + " avg. len = " + ((float) length)/count);
        }
         */

        View view = new View(filterSpec, groupFilter, rowFilter, sortOrder, postFilterGroups);
        this.lastView = view;
        return view;
    }

    public View getView (String filterSpec, String groupViewControlSpec, String rowViewControlSpec, String sortOrder) {
        return getView (filterSpec, GroupViewControl.valueOf(groupViewControlSpec), RowViewControl.valueOf(rowViewControlSpec), sortOrder);
    }

    /** returns a collection of groups after filtering groups by the given filter.
     * under each group is a bunch of ids. under each id has a bunch of rows.
	 * that's why it returns a List of List of List of Rows.
	 * it is assumed that:
	 * one row is only in one group, i.e. it can't belong to multiple groups.
	 * and all rows belonging to an id are also in the same group.
	 * (this should be satisfied if the inputGroupsList has been generated by a Union-Find set. */
	private List<List<List<Row>>> applyFilter (Filter filter, GroupViewControl gvc, RowViewControl rvc) {

		List<List<List<Row>>> result = new ArrayList<>();

		for (Collection<Row> rowCollection: groups) {
            List<Row> group = new ArrayList<>(rowCollection); // convert collection to list

            // will this group be shown? yes, if ANY of the rows in this group matches the filter. otherwise move on to the next group.
            {
                boolean groupWillBeShown = true;
                switch (gvc) {
                    case GROUPS_WITH_TWO_OR_MORE_ROWS:
                        groupWillBeShown = group.stream().filter(filter::passes).limit(2).count() >= 2; // limit(2) to ensure early out at finding the first row passing the filter
                        break;
                    case GROUPS_WITH_ONE_OR_MORE_ROWS:
                        groupWillBeShown = group.stream().filter(filter::passes).limit(1).count() >= 1; // limit(2) to ensure early out at finding the first row passing the filter
                        break;
                    case GROUPS_WITH_TWO_OR_MORE_IDS:
                        Set<String> idsInGroup = group.stream().filter(filter::passes).map(r -> r.get(Config.ID_FIELD)).collect(Collectors.toSet()); // limit(2) to ensure early out at finding the first row passing the filter
                        groupWillBeShown = (idsInGroup.size() >= 2);
                        break;
                }

                if (!groupWillBeShown)
                    continue;
            }

			// ok, this group is to be shown. sort the rows in this group data based on sortColumns
			{
				String[] sortColumns = Config.sortColumns;

				Comparator c = (Comparator<Row>) (r1, r2) -> {
                    for (String col : sortColumns) {
                        int result1 = r1.get(col).toLowerCase().compareTo(r2.get(col).toLowerCase());
                        if (result1 != 0)
                            return result1;
                    }
                    return 0;
                };
				group.sort(c);
			}

			// generate a id -> RowsInThisGroup map
			Multimap<String, Row> idToRowsInThisGroup = LinkedHashMultimap.create();
			group.forEach(r -> {
				idToRowsInThisGroup.put(r.get(Config.ID_FIELD), r);
			});

			// prepare list of list of rows for this group
			List<List<Row>> rowsForThisGroup = new ArrayList<>();
			for (String id: idToRowsInThisGroup.keySet()) {
				List<Row> rowsForThisId = new ArrayList<>(idToRowsInThisGroup.get(id));

                List<Row> resultListForThisId;
                if (rvc == IDS_MATCHING_FILTER) {
                    // if any row for this id passes filter, we pass all the rows to resultListForThisId
                    boolean doesAnyRowForThisIdMatch = rowsForThisId.stream().filter (filter::passes).limit(1).count() > 0;
                    if (doesAnyRowForThisIdMatch)
                        resultListForThisId = rowsForThisId;
                    else
                        continue;
                } else if (rvc == ROWS_MATCHING_FILTER){
				    resultListForThisId = rowsForThisId.stream().filter (filter::passes).collect(Collectors.toList());
                } else { // if (rvc == ALL_ROWS) {
                    resultListForThisId = rowsForThisId;
                }

                if (resultListForThisId.size() > 0)
                    rowsForThisGroup.add (resultListForThisId);
			}

			if (rowsForThisGroup.size() > 0)
    			result.add (rowsForThisGroup);
		}

		return result;
	}

	public String toString() {
	    return "Merge manager # of groups: " + (groups != null ? groups.size() : "(merge not run)" + " last view: " + lastView);
    }
}

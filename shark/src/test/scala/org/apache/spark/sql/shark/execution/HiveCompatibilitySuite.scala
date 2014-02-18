package org.apache.spark.sql
package shark
package execution


import java.io._

import util._

/**
 * Runs the test cases that are included in the hive distribution.
 */
class HiveCompatibilitySuite extends HiveQueryFileTest {
  // TODO: bundle in jar files... get from classpath
  lazy val hiveQueryDir = TestShark.getHiveFile("ql/src/test/queries/clientpositive")
  def testCases = hiveQueryDir.listFiles.map(f => f.getName.stripSuffix(".q") -> f)

  /** A list of tests deemed out of scope currently and thus completely disregarded. */
  override def blackList = Seq(
    // These tests use hooks that are not on the classpath and thus break all subsequent execution.
    "hook_order",
    "hook_context",
    "mapjoin_hook",
    "multi_sahooks",
    "overridden_confs",
    "query_properties",
    "sample10",
    "updateAccessTime",
    "index_compact_binary_search",
    "bucket_num_reducers",
    "column_access_stats",
    "concatenate_inherit_table_location",

    // Setting a default property does not seem to get reset and thus changes the answer for many
    // subsequent tests.
    "create_default_prop",

    // User specific test answers, breaks the caching mechanism.
    "authorization_3",
    "authorization_5",
    "keyword_1",
    "misc_json",
    "create_like_tbl_props",
    "load_overwrite",
    "alter_table_serde2",
    "alter_table_not_sorted",
    "alter_skewed_table",
    "alter_partition_clusterby_sortby",
    "alter_merge",
    "alter_concatenate_indexed_table",
    "protectmode2",
    "describe_table",
    "describe_comment_nonascii",
    "udf5",

    // Weird DDL differences result in failures on jenkins.
    "create_like2",
    "create_view_translate",
    "partitions_json",

    // Timezone specific test answers.
    "udf_unix_timestamp",
    "udf_to_unix_timestamp",

    // Cant run without local map/reduce.
    "index_auto_update",
    "index_auto_self_join",
    "index_stale.*",
    "type_cast_1",
    "index_compression",
    "index_bitmap_compression",
    "index_auto_multiple",
    "index_auto_mult_tables_compact",
    "index_auto_mult_tables",
    "index_auto_file_format",
    "index_auth",
    "index_auto_empty",
    "index_auto_partitioned",
    "index_auto_unused",
    "index_bitmap_auto_partitioned",
    "ql_rewrite_gbtoidx",
    "stats1.*",
    "alter_merge_stats",

    // Hive seems to think 1.0 > NaN = true && 1.0 < NaN = false... which is wrong.
    // http://stackoverflow.com/a/1573715
    "ops_comparison",

    // Tests that seems to never complete on hive...
    "skewjoin",
    "database",

    // These tests fail and and exit the JVM.
    "auto_join18_multi_distinct",
    "join18_multi_distinct",
    "input44",
    "input42",
    "input_dfs",
    "metadata_export_drop",
    "repair",

    // Uses a serde that isn't on the classpath... breaks other tests.
    "bucketizedhiveinputformat",

    // Avro tests seem to change the output format permanently thus breaking the answer cache, until
    // we figure out why this is the case let just ignore all of avro related tests.
    ".*avro.*",

    // Unique joins are weird and will require a lot of hacks (see comments in hive parser).
    "uniquejoin",

    // Hive seems to get the wrong answer on some outer joins.  MySQL agrees with catalyst.
    "auto_join29",

    // No support for multi-alias i.e. udf as (e1, e2, e3).
    "allcolref_in_udf",

    // No support for TestSerDe (not published afaik)
    "alter1",
    "input16",

    // No support for unpublished test udfs.
    "autogen_colalias",

    // Shark does not support buckets.
    ".*bucket.*",

    // No window support yet
    ".*window.*",

    // Fails in hive with authorization errors.
    "alter_rename_partition_authorization",
    "authorization.*",

    // Hadoop version specific tests
    "archive_corrupt",

    // No support for case sensitivity is resolution using hive properties atm.
    "case_sensitivity"
  )

  /**
   * The set of tests that are believed to be working in catalyst. Tests not on whiteList or
   * blacklist are implicitly marked as ignored.
   */
  override def whiteList = Seq(
    "add_part_exist",
    "add_partition_no_whitelist",
    "add_partition_with_whitelist",
    "alias_casted_column",
    "alter2",
    "alter4",
    "alter5",
    "alter_index",
    "alter_merge_2",
    "alter_partition_format_loc",
    "alter_partition_protect_mode",
    "alter_partition_with_whitelist",
    "alter_table_serde",
    "alter_varchar2",
    "alter_view_as_select",
    "ambiguous_col",
    "auto_join0",
    "auto_join1",
    "auto_join10",
    "auto_join11",
    "auto_join12",
    "auto_join13",
    "auto_join14",
    "auto_join14_hadoop20",
    "auto_join15",
    "auto_join17",
    "auto_join18",
    "auto_join19",
    "auto_join2",
    "auto_join20",
    "auto_join21",
    "auto_join22",
    "auto_join23",
    "auto_join24",
    "auto_join25",
    "auto_join26",
    "auto_join27",
    "auto_join28",
    "auto_join3",
    "auto_join30",
    "auto_join31",
    "auto_join32",
    "auto_join4",
    "auto_join5",
    "auto_join6",
    "auto_join7",
    "auto_join8",
    "auto_join9",
    "auto_join_filters",
    "auto_join_nulls",
    "auto_join_reordering_values",
    "auto_sortmerge_join_1",
    "auto_sortmerge_join_10",
    "auto_sortmerge_join_11",
    "auto_sortmerge_join_12",
    "auto_sortmerge_join_15",
    "auto_sortmerge_join_2",
    "auto_sortmerge_join_3",
    "auto_sortmerge_join_4",
    "auto_sortmerge_join_5",
    "auto_sortmerge_join_6",
    "auto_sortmerge_join_7",
    "auto_sortmerge_join_8",
    "auto_sortmerge_join_9",
    "binary_constant",
    "binarysortable_1",
    "combine1",
    "compute_stats_binary",
    "compute_stats_boolean",
    "compute_stats_double",
    "compute_stats_table",
    "compute_stats_long",
    "compute_stats_string",
    "convert_enum_to_string",
    "correlationoptimizer11",
    "correlationoptimizer15",
    "correlationoptimizer2",
    "correlationoptimizer3",
    "correlationoptimizer4",
    "correlationoptimizer6",
    "correlationoptimizer7",
    "correlationoptimizer8",
    "count",
    "create_like_view",
    "create_nested_type",
    "create_skewed_table1",
    "create_struct_table",
    "ct_case_insensitive",
    "database_location",
    "database_properties",
    "decimal_join",
    "default_partition_name",
    "delimiter",
    "desc_non_existent_tbl",
    "describe_comment_indent",
    "describe_database_json",
    "describe_pretty",
    "describe_syntax",
    "describe_table_json",
    "diff_part_input_formats",
    "disable_file_format_check",
    "drop_function",
    "drop_index",
    "drop_partitions_filter",
    "drop_partitions_filter2",
    "drop_partitions_filter3",
    "drop_partitions_ignore_protection",
    "drop_table",
    "drop_table2",
    "drop_view",
    "escape_clusterby1",
    "escape_distributeby1",
    "escape_orderby1",
    "escape_sortby1",
    "fetch_aggregation",
    "filter_join_breaktask",
    "filter_join_breaktask2",
    "groupby1",
    "groupby11",
    "groupby1_map",
    "groupby1_map_nomap",
    "groupby1_map_skew",
    "groupby1_noskew",
    "groupby4",
    "groupby4_map",
    "groupby4_map_skew",
    "groupby4_noskew",
    "groupby5",
    "groupby5_map",
    "groupby5_map_skew",
    "groupby5_noskew",
    "groupby6",
    "groupby6_map",
    "groupby6_map_skew",
    "groupby6_noskew",
    "groupby7",
    "groupby7_map",
    "groupby7_map_multi_single_reducer",
    "groupby7_map_skew",
    "groupby7_noskew",
    "groupby8_map",
    "groupby8_map_skew",
    "groupby8_noskew",
    "groupby_distinct_samekey",
    "groupby_multi_single_reducer2",
    "groupby_mutli_insert_common_distinct",
    "groupby_neg_float",
    "groupby_sort_10",
    "groupby_sort_6",
    "groupby_sort_8",
    "groupby_sort_test_1",
    "implicit_cast1",
    "innerjoin",
    "inoutdriver",
    "input",
    "input0",
    "input11",
    "input11_limit",
    "input12",
    "input12_hadoop20",
    "input19",
    "input1_limit",
    "input22",
    "input23",
    "input24",
    "input25",
    "input26",
    "input28",
    "input2_limit",
    "input40",
    "input41",
    "input4_cb_delim",
    "input6",
    "input7",
    "input8",
    "input9",
    "input_limit",
    "input_part0",
    "input_part1",
    "input_part10",
    "input_part10_win",
    "input_part2",
    "input_part3",
    "input_part4",
    "input_part5",
    "input_part6",
    "input_part7",
    "input_part8",
    "input_part9",
    "inputddl4",
    "inputddl7",
    "inputddl8",
    "insert_compressed",
    "join0",
    "join1",
    "join10",
    "join11",
    "join12",
    "join13",
    "join14",
    "join14_hadoop20",
    "join15",
    "join16",
    "join17",
    "join18",
    "join19",
    "join2",
    "join20",
    "join21",
    "join22",
    "join23",
    "join24",
    "join25",
    "join26",
    "join27",
    "join28",
    "join29",
    "join3",
    "join30",
    "join31",
    "join32",
    "join33",
    "join34",
    "join35",
    "join36",
    "join37",
    "join38",
    "join39",
    "join4",
    "join40",
    "join41",
    "join5",
    "join6",
    "join7",
    "join8",
    "join9",
    "join_1to1",
    "join_array",
    "join_casesensitive",
    "join_empty",
    "join_filters",
    "join_hive_626",
    "join_nulls",
    "join_reorder2",
    "join_reorder3",
    "join_reorder4",
    "join_star",
    "join_view",
    "lateral_view_cp",
    "lateral_view_ppd",
    "lineage1",
    "literal_double",
    "literal_ints",
    "literal_string",
    "load_dyn_part7",
    "load_file_with_space_in_the_name",
    "louter_join_ppr",
    "mapjoin_distinct",
    "mapjoin_mapjoin",
    "mapjoin_subquery",
    "mapjoin_subquery2",
    "mapjoin_test_outer",
    "mapreduce3",
    "mapreduce7",
    "merge1",
    "merge2",
    "mergejoins",
    "mergejoins_mixed",
    "multiMapJoin1",
    "multiMapJoin2",
    "multi_join_union",
    "multigroupby_singlemr",
    "noalias_subq1",
    "nomore_ambiguous_table_col",
    "nonblock_op_deduplicate",
    "notable_alias1",
    "notable_alias2",
    "nullgroup",
    "nullgroup2",
    "nullgroup3",
    "nullgroup4",
    "nullgroup4_multi_distinct",
    "nullgroup5",
    "nullinput",
    "nullinput2",
    "nullscript",
    "optional_outer",
    "order",
    "order2",
    "outer_join_ppr",
    "part_inherit_tbl_props",
    "part_inherit_tbl_props_empty",
    "part_inherit_tbl_props_with_star",
    "partition_schema1",
    "partition_varchar1",
    "plan_json",
    "ppd1",
    "ppd_constant_where",
    "ppd_gby",
    "ppd_gby2",
    "ppd_gby_join",
    "ppd_join",
    "ppd_join2",
    "ppd_join3",
    "ppd_join_filter",
    "ppd_outer_join1",
    "ppd_outer_join2",
    "ppd_outer_join3",
    "ppd_outer_join4",
    "ppd_outer_join5",
    "ppd_random",
    "ppd_repeated_alias",
    "ppd_udf_col",
    "ppd_union",
    "ppr_allchildsarenull",
    "ppr_pushdown",
    "ppr_pushdown2",
    "ppr_pushdown3",
    "progress_1",
    "protectmode",
    "push_or",
    "query_with_semi",
    "quote1",
    "quote2",
    "reduce_deduplicate_exclude_join",
    "rename_column",
    "router_join_ppr",
    "select_as_omitted",
    "select_unquote_and",
    "select_unquote_not",
    "select_unquote_or",
    "serde_reported_schema",
    "set_variable_sub",
    "show_describe_func_quotes",
    "show_functions",
    "show_partitions",
    "skewjoinopt13",
    "skewjoinopt18",
    "skewjoinopt9",
    "smb_mapjoin_1",
    "smb_mapjoin_10",
    "smb_mapjoin_13",
    "smb_mapjoin_14",
    "smb_mapjoin_15",
    "smb_mapjoin_16",
    "smb_mapjoin_17",
    "smb_mapjoin_2",
    "smb_mapjoin_21",
    "smb_mapjoin_25",
    "smb_mapjoin_3",
    "smb_mapjoin_4",
    "smb_mapjoin_5",
    "smb_mapjoin_8",
    "sort",
    "sort_merge_join_desc_1",
    "sort_merge_join_desc_2",
    "sort_merge_join_desc_3",
    "sort_merge_join_desc_4",
    "sort_merge_join_desc_5",
    "sort_merge_join_desc_6",
    "sort_merge_join_desc_7",
    "stats0",
    "stats20",
    "stats_empty_partition",
    "subq2",
    "tablename_with_select",
    "touch",
    "type_widening",
    "udaf_collect_set",
    "udaf_corr",
    "udaf_covar_pop",
    "udaf_covar_samp",
    "udf2",
    "udf6",
    "udf9",
    "udf_10_trims",
    "udf_E",
    "udf_PI",
    "udf_abs",
    "udf_acos",
    "udf_add",
    "udf_array",
    "udf_array_contains",
    "udf_ascii",
    "udf_asin",
    "udf_atan",
    "udf_avg",
    "udf_bigint",
    "udf_bin",
    "udf_bitmap_and",
    "udf_bitmap_empty",
    "udf_bitmap_or",
    "udf_bitwise_and",
    "udf_bitwise_not",
    "udf_bitwise_or",
    "udf_bitwise_xor",
    "udf_boolean",
    "udf_case",
    "udf_ceil",
    "udf_ceiling",
    "udf_concat",
    "udf_concat_insert2",
    "udf_concat_ws",
    "udf_conv",
    "udf_cos",
    "udf_count",
    "udf_date_add",
    "udf_date_sub",
    "udf_datediff",
    "udf_day",
    "udf_dayofmonth",
    "udf_degrees",
    "udf_div",
    "udf_double",
    "udf_exp",
    "udf_field",
    "udf_find_in_set",
    "udf_float",
    "udf_floor",
    "udf_format_number",
    "udf_from_unixtime",
    "udf_greaterthan",
    "udf_greaterthanorequal",
    "udf_hex",
    "udf_if",
    "udf_index",
    "udf_int",
    "udf_isnotnull",
    "udf_isnull",
    "udf_java_method",
    "udf_lcase",
    "udf_length",
    "udf_lessthan",
    "udf_lessthanorequal",
    "udf_like",
    "udf_ln",
    "udf_log",
    "udf_log10",
    "udf_log2",
    "udf_lower",
    "udf_lpad",
    "udf_ltrim",
    "udf_map",
    "udf_minute",
    "udf_modulo",
    "udf_month",
    "udf_negative",
    "udf_not",
    "udf_notequal",
    "udf_notop",
    "udf_nvl",
    "udf_or",
    "udf_parse_url",
    "udf_positive",
    "udf_pow",
    "udf_power",
    "udf_radians",
    "udf_rand",
    "udf_regexp",
    "udf_regexp_extract",
    "udf_regexp_replace",
    "udf_repeat",
    "udf_rlike",
    "udf_round",
    "udf_round_3",
    "udf_rpad",
    "udf_rtrim",
    "udf_second",
    "udf_sign",
    "udf_sin",
    "udf_smallint",
    "udf_space",
    "udf_sqrt",
    "udf_std",
    "udf_stddev",
    "udf_stddev_pop",
    "udf_stddev_samp",
    "udf_string",
    "udf_substring",
    "udf_subtract",
    "udf_sum",
    "udf_tan",
    "udf_tinyint",
    "udf_to_byte",
    "udf_to_date",
    "udf_to_double",
    "udf_to_float",
    "udf_to_long",
    "udf_to_short",
    "udf_translate",
    "udf_trim",
    "udf_ucase",
    "udf_upper",
    "udf_var_pop",
    "udf_var_samp",
    "udf_variance",
    "udf_weekofyear",
    "udf_when",
    "udf_xpath",
    "udf_xpath_boolean",
    "udf_xpath_double",
    "udf_xpath_float",
    "udf_xpath_int",
    "udf_xpath_long",
    "udf_xpath_short",
    "udf_xpath_string",
    "unicode_notation",
    "union10",
    "union11",
    "union13",
    "union14",
    "union15",
    "union16",
    "union17",
    "union18",
    "union19",
    "union2",
    "union20",
    "union22",
    "union23",
    "union24",
    "union26",
    "union27",
    "union28",
    "union29",
    "union30",
    "union31",
    "union34",
    "union4",
    "union5",
    "union6",
    "union7",
    "union8",
    "union9",
    "union_lateralview",
    "union_ppr",
    "union_remove_3",
    "union_remove_6",
    "union_script",
    "varchar_2",
    "varchar_join1",
    "varchar_union1"
  )
}

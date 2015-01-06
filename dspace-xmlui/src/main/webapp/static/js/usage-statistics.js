/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
(function ($) {

    /**
     * Function ensures that when a new time filter is selected the form is submitted
     */
    $(document).ready(function() {
        $('select[name="time_filter"]').change(function(){
            $(this).parents('form:first').submit();

        });
        
        $("#aspect_statistics_StatisticsSearchTransformer_field_end_date").datepicker({dateFormat: 'yy-mm-dd'});
        $("#aspect_statistics_StatisticsSearchTransformer_field_start_date").datepicker({dateFormat: 'yy-mm-dd'});
        
        $("#aspect_statistics_StatisticsSearchTransformer_field_start_date" ).val($("#aspect_statistics_StatisticsSearchTransformer_field_start_date_hidden").val());
        $("#aspect_statistics_StatisticsSearchTransformer_field_end_date" ).val($("#aspect_statistics_StatisticsSearchTransformer_field_end_date_hidden").val());

        $("#aspect_statistics_StatisticsSearchTransformer_field_end_date" ).datepicker();
        $("#aspect_statistics_StatisticsSearchTransformer_field_start_date" ).datepicker();
        
        $("#aspect_statistics_StatisticsSearchTransformer_field_start_date" ).blur();
//        
//        $(".ds-text-field").change(function(){
//            $(this).val("i am changed!");
//        });
        
        $("#aspect_statistics_StatisticsSearchTransformer_field_start_date").change(function(){
            $("input[name='query']").val("here");
            //$(this).delay(800).val($(this).val()).blur();
        });
        
        $('#aspect_statistics_StatisticsSearchTransformer_field_time_range_filter_btn').click(function(){
            $(this).parents('form:first').submit();
        });
        
    });
})(jQuery);

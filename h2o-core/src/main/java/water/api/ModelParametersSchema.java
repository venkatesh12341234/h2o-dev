package water.api;

import hex.Model;
import hex.ModelBuilder;
import hex.ModelBuilder.ValidationMessage;
import hex.ModelBuilder.ValidationMessage.MessageType;
import water.*;
import water.api.KeyV3.FrameKeyV3;
import water.api.KeyV3.ModelKeyV3;
import water.fvec.Frame;
import water.util.Log;
import water.util.PojoUtils;

import java.lang.reflect.Field;
import java.util.*;

/**
 * An instance of a ModelParameters schema contains the Model build parameters (e.g., K and max_iterations for KMeans).
 * NOTE: use subclasses, not this class directly.  It is not abstract only so that we can instantiate it to generate metadata
 * for it for the metadata API.
 */
public class ModelParametersSchema<P extends Model.Parameters, S extends ModelParametersSchema<P, S>> extends Schema<P, S> {
  ////////////////////////////////////////
  // NOTE:
  // Parameters must be ordered for the UI
  ////////////////////////////////////////
  static public String[] own_fields = new String[] { "model_id", "training_frame", "validation_frame", "ignored_columns", "drop_na20_cols", "score_each_iteration" };

  /** List of fields in the order in which we want them serialized.  This is the order they will be presented in the UI.  */
  private transient String[] __fields_cache = null;

  public String[] fields() {
    if (null == __fields_cache) {
      __fields_cache = new String[0];
      Class<? extends ModelParametersSchema> this_clz = this.getClass();

      try {
        for (Class<? extends ModelParametersSchema> clz = this_clz; ; clz = (Class<? extends ModelParametersSchema>) clz.getSuperclass()) {
          String[] fields = (String[]) clz.getField("own_fields").get(clz);

          String[] tmp = new String[fields.length + __fields_cache.length];
          System.arraycopy(fields, 0, tmp, 0, fields.length);
          System.arraycopy(__fields_cache, 0, tmp, fields.length, __fields_cache.length);
          __fields_cache = tmp;

          if (clz == ModelParametersSchema.class) break;
        }
      }
      catch (Exception e) {
        throw H2O.fail("Caught exception appending the schema field list for: " + this);
      }
    }
    return __fields_cache;
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAREFUL: This class has its own JSON serializer.  If you add a field here you probably also want to add it to the serializer!
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Parameters common to all models:
  @API(help="Destination key for this model; auto-generated if not specified", required = false, direction=API.Direction.INOUT)
  public ModelKeyV3 model_id;

  @API(help="Training frame", direction=API.Direction.INOUT /* Not required, to allow initial params validation: , required=true */)
  public FrameKeyV3 training_frame;

  @API(help="Validation frame", direction=API.Direction.INOUT)
  public FrameKeyV3 validation_frame;

  @API(help="Ignored columns", is_member_of_frames={"training_frame", "validation_frame"}, direction=API.Direction.INOUT)
  public String[] ignored_columns;         // column names to ignore for training

  @API(help="Drop columns with more than 20% missing values", direction=API.Direction.INOUT)
  public boolean drop_na20_cols; // Drop columns with more than 20% missing values

  @API(help="Whether to score during each iteration of model training", direction=API.Direction.INOUT)
  public boolean score_each_iteration;

  protected static String[] append_field_arrays(String[] first, String[] second) {
    String[] appended = new String[first.length + second.length];
    System.arraycopy(first, 0, appended, 0, first.length);
    System.arraycopy(second, 0, appended, first.length, second.length);
    return appended;
  }

  public S fillFromImpl(P impl) {
    PojoUtils.copyProperties(this, impl, PojoUtils.FieldNaming.ORIGIN_HAS_UNDERSCORES );

    if (null != impl._train) {
      Value v = DKV.get(impl._train);
      if (null != v) {
        training_frame = new FrameKeyV3(((Frame) v.get())._key);
      }
    }

    if (null != impl._valid) {
      Value v = DKV.get(impl._valid);
      if (null != v) {
        validation_frame = new FrameKeyV3(((Frame) v.get())._key);
      }
    }

    return (S)this;
  }

  public P fillImpl(P impl) {
    super.fillImpl(impl);

    impl._train = (null == this.training_frame ? null : Key.make(this.training_frame.name));
    impl._valid = (null == this.validation_frame ? null : Key.make(this.validation_frame.name));

    return impl;
  }

  public static class ValidationMessageBase<I extends ModelBuilder.ValidationMessage, S extends ValidationMessageBase<I, S>> extends Schema<I, S> {
    @API(help="Type of validation message (ERROR, WARN, INFO, HIDE)", direction=API.Direction.OUTPUT)
    public String message_type;

    @API(help="Field to which the message applies", direction=API.Direction.OUTPUT)
    public String field_name;

    @API(help="Message text", direction=API.Direction.OUTPUT)
    public String message;

    public I createImpl() { return (I) new ModelBuilder.ValidationMessage(MessageType.valueOf(message_type), field_name, message); };

    // Version&Schema-specific filling from the implementation object
    public S fillFromImpl(ValidationMessage vm) {
      PojoUtils.copyProperties(this, vm, PojoUtils.FieldNaming.CONSISTENT);
      if (this.field_name != null) {
        if (this.field_name.startsWith("_"))
          this.field_name = this.field_name.substring(1);
        else
          Log.warn("Expected all ValidationMessage field_name values to have leading underscores; ignoring: " + field_name);
      }
      return (S)this;
    }
  }

  public static final class ValidationMessageV2 extends ValidationMessageBase<ModelBuilder.ValidationMessage, ValidationMessageV2> {  }

  private static void compute_transitive_closure_of_is_mutually_exclusive(ModelParameterSchemaV3[] metadata) {
    // Form the transitive closure of the is_mutually_exclusive field lists by visiting
    // all fields and collecting the fields in a Map of Sets.  Then pass over them a second
    // time setting the full lists.
    Map<String, Set<String>> field_exclusivity_groups = new HashMap<>();
    for (int i = 0; i < metadata.length; i++) {
      ModelParameterSchemaV3 param = metadata[i];
      String name = param.name;

      // Turn param.is_mutually_exclusive_with into a List which we will walk over twice
      List<String> me = new ArrayList<String>();
      me.add(name);
      // Note: this can happen if this field doesn't have an @API annotation, in which case we got an earlier WARN
      if (null != param.is_mutually_exclusive_with) me.addAll(Arrays.asList(param.is_mutually_exclusive_with));

      // Make a new Set which contains ourselves, fields we have already been connected to,
      // and fields *they* have already been connected to.
      Set new_set = new HashSet();
      for (String s : me) {
        // Were we mentioned by a previous field?
        if (field_exclusivity_groups.containsKey(s))
          new_set.addAll(field_exclusivity_groups.get(s));
        else
          new_set.add(s);
      }

      // Now point all the fields in our Set to the Set.
      for (String s : me) {
        field_exclusivity_groups.put(s, new_set);
      }
    }

    // Now walk over all the fields and create new comprehensive is_mutually_exclusive arrays, not containing self.
    for (int i = 0; i < metadata.length; i++) {
      ModelParameterSchemaV3 param = metadata[i];
      String name = param.name;
      Set<String> me = field_exclusivity_groups.get(name);
      Set<String> not_me = new HashSet(me);
      not_me.remove(name);
      param.is_mutually_exclusive_with = not_me.toArray(new String[not_me.size()]);
    }
  }

  /**
   * Write the parameters, including their metadata, into an AutoBuffer.  Used by
   * ModelBuilderSchema#writeJSON_impl and ModelSchema#writeJSON_impl.
   */
  public static final AutoBuffer writeParametersJSON( AutoBuffer ab, ModelParametersSchema parameters, ModelParametersSchema default_parameters) {
    String[] fields = parameters.fields();

    // Build ModelParameterSchemaV2 objects for each field, and the call writeJSON on the array
    ModelParameterSchemaV3[] metadata = new ModelParameterSchemaV3[fields.length];

    String field_name = null;
    try {
      for (int i = 0; i < fields.length; i++) {
        field_name = fields[i];
        Field f = parameters.getClass().getField(field_name);

        // TODO: cache a default parameters schema
        ModelParameterSchemaV3 schema = new ModelParameterSchemaV3(parameters, default_parameters, f);
        metadata[i] = schema;
      }
    } catch (NoSuchFieldException e) {
      throw H2O.fail("Caught exception accessing field: " + field_name + " for schema object: " + parameters + ": " + e.toString());
    }

    compute_transitive_closure_of_is_mutually_exclusive(metadata);

    ab.putJSONA("parameters", metadata);
    return ab;
  }
}

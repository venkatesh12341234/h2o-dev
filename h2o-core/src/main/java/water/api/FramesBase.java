package water.api;

import water.api.FramesHandler.Frames;
import water.api.KeyV3.FrameKeyV3;
import water.fvec.Frame;

class FramesBase<I extends Frames, S extends FramesBase<I, S>> extends Schema<I, FramesBase<I, S>> {
  // Input fields
  @API(help="Name of Frame of interest", json=false)
  public FrameKeyV3 frame_id;

  @API(help="Name of column of interest", json=false)
  public String column;

  @API(help="Row offset to display", direction=API.Direction.INOUT)
  public long row_offset;

  @API(help="Number of rows to display", direction=API.Direction.INOUT)
  public int row_count;

  @API(help="Find and return compatible models?", json=false)
  public boolean find_compatible_models = false;

  @API(help="File output path",json=false)
  public String path;

  @API(help="Overwrite existing fil",json=false)
  public boolean force;

  // Output fields
  @API(help="Frames", direction=API.Direction.OUTPUT)
  FrameV3[] frames;

  @API(help="Compatible models", direction=API.Direction.OUTPUT)
  ModelSchema[] compatible_models;

  @API(help="Domains", direction=API.Direction.OUTPUT)
  String[][] domain;

  // Non-version-specific filling into the impl
  @Override public I fillImpl(I f) {
    super.fillImpl(f);

    if (null != frames) {
      f.frames = new Frame[frames.length];

      int i = 0;
      for (FrameV3 frame : this.frames) {
        f.frames[i++] = frame._fr;
      }
    }
    return f;
  }

  // TODO: parameterize on the FrameVx Schema class
  @Override public FramesBase fillFromImpl(Frames f) {
    this.frame_id = new FrameKeyV3(f.frame_id);
    this.column = f.column; // NOTE: this is needed for request handling, but isn't really part of state
    this.find_compatible_models = f.find_compatible_models;

    if (null != f.frames) {
      this.frames = new FrameV3[f.frames.length];

      int i = 0;
      for (Frame frame : f.frames) {
        this.frames[i++] = new FrameV3(frame, f.offset, f.len);
      }
    }
    return this;
  }
}
